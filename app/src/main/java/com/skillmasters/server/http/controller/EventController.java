package com.skillmasters.server.http.controller;

import java.util.Map;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.lang.reflect.Field;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.springframework.security.web.bind.annotation.AuthenticationPrincipal;
import org.springframework.util.ReflectionUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import io.swagger.annotations.*;

import biweekly.io.ParseContext;
import biweekly.io.scribe.property.RecurrenceRuleScribe;
import biweekly.util.com.google.ical.compat.javautil.DateIterator;
import biweekly.parameter.ICalParameters;
import biweekly.property.RecurrenceRule;

import com.google.common.base.Strings;
import com.google.common.base.CaseFormat;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;

import com.skillmasters.server.misc.OffsetPageRequest;
import com.skillmasters.server.http.response.EventResponse;
import com.skillmasters.server.http.response.EventInstanceResponse;
import com.skillmasters.server.service.PermissionService;
import com.skillmasters.server.service.EventService;
import com.skillmasters.server.model.User;
import com.skillmasters.server.model.Event;
import com.skillmasters.server.model.EventPattern;
import com.skillmasters.server.model.QEvent;
import com.skillmasters.server.model.QPermission;

@RestController
@RequestMapping("/api/v1")
@Api(tags="Events", description="planner's events")
public class EventController
{
  @Autowired
  EventService service;

  @Autowired
  PermissionService permissionService;

  @Autowired
  RecurrenceRuleScribe scribe;

  @Autowired
  ParseContext context;

  @PersistenceContext
  EntityManager entityManager;

  @ApiOperation(value = "Get a list of available events instances", response = EventInstanceResponse.class)
  @GetMapping("/events/instances")
  public EventInstanceResponse retrieveInstances(
    @AuthenticationPrincipal User user,
    @RequestParam(value="id", defaultValue="") List<Long> id,
    @RequestParam(value="owner_id", required=false) String ownerId,
    @RequestParam(value="from", required=false) Long from,
    @RequestParam(value="to", required=false) Long to,
    @RequestParam(value="created_from", required=false) Long createdFrom,
    @RequestParam(value="created_to", required=false) Long createdTo,
    @RequestParam(value="updated_from", required=false) Long updatedFrom,
    @RequestParam(value="updated_to", required=false) Long updatedTo
  ) {
    EventInstanceResponse response = new EventInstanceResponse();
    JPAQuery query = generateGetQuery(user, id, ownerId, from, to, createdFrom, createdTo, updatedFrom, updatedTo);

    Date fromDate;
    if (from == null) {
      fromDate = new Date(0);
    } else {
      fromDate = new Date(from);
    }

    Date toDate;
    if (to == null) {
      toDate = new Date(Long.MAX_VALUE);
    } else {
      toDate = new Date(to);
    }

    TimeZone utcTimezone = TimeZone.getTimeZone("UTC");
    TimeZone timezone = utcTimezone;
    DateFormat df = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
    df.setTimeZone(utcTimezone);

    Date eventDate;
    Iterable<Event> events = service.getByQuery(query);
    for (Event event : events) {
      for (EventPattern pattern : event.getPatterns()) {
        String rruleStr = pattern.getRrule();
        if (rruleStr == null) {
          response.addInstance(event, pattern);
        } else {
          Date start = pattern.getStartedAt();
          Date end = pattern.getEndedAt().before(toDate) ? pattern.getEndedAt() : toDate;
          rruleStr += ";UNTIL=" + df.format(end);

          if (Strings.isNullOrEmpty(pattern.getTimezone())) {
            timezone = utcTimezone;
          } else {
            timezone = TimeZone.getTimeZone(pattern.getTimezone());
          }

          RecurrenceRule rrule = scribe.parseText(rruleStr, null, new ICalParameters(), context);
          DateIterator dateIt = rrule.getDateIterator(start, timezone);

          Date firstEnd = new Date(start.getTime() + pattern.getDuration());
          if (fromDate.after(firstEnd)) {
            dateIt.advanceTo(fromDate);
          }

          for (int i = 0; i < 100 && dateIt.hasNext(); ++i) {
            eventDate = dateIt.next();
            response.addInstance(event, pattern, eventDate, new Date(eventDate.getTime() + pattern.getDuration()));
          }
        }
      }
    }
    return response.success();
  }

  @ApiOperation(value = "Get a list of available events", response = EventResponse.class)
  @GetMapping("/events")
  public EventResponse retrieve(
    @AuthenticationPrincipal User user,
    @RequestParam(value="offset", defaultValue="0") long offset,
    @RequestParam(value="count", defaultValue="100") int count,
    @RequestParam(value="id", defaultValue="") List<Long> id,
    @RequestParam(value="owner_id", required=false) String ownerId,
    @RequestParam(value="from", required=false) Long from,
    @RequestParam(value="to", required=false) Long to,
    @RequestParam(value="created_from", required=false) Long createdFrom,
    @RequestParam(value="created_to", required=false) Long createdTo,
    @RequestParam(value="updated_from", required=false) Long updatedFrom,
    @RequestParam(value="updated_to", required=false) Long updatedTo
  ) {
    JPAQuery query = generateGetQuery(user, id, ownerId, from, to, createdFrom, createdTo, updatedFrom, updatedTo);
    return new EventResponse().success( service.getByQuery(query, new OffsetPageRequest(offset, count)) );
  }

  @ApiOperation(value = "Get event by id", response = EventResponse.class)
  @GetMapping("/events/{id}")
  public EventResponse retrieveById(@PathVariable Long id)
  {
    Event entity = service.getById(id);
    if (entity == null) {
      return new EventResponse().error(404, "Event not found");
    }
    return new EventResponse().success(entity);
  }

  @ApiOperation(value = "Create event", response = EventResponse.class)
  @PostMapping("/events")
  public EventResponse create(@AuthenticationPrincipal User user, @RequestBody Event event)
  {
    event.setOwnerId(user.getId());
    return new EventResponse().success( service.save(event) );
  }

  @ApiImplicitParams(
    @ApiImplicitParam(
      name = "updates",
      value = "Object with updated values for Event",
      required = true,
      dataType = "Event"
    )
  )
  @ApiOperation(value = "Update event", response = EventResponse.class)
  @PatchMapping("/events/{id}")
  public EventResponse update(@PathVariable Long id, @RequestBody Map<String, Object> updates)
  {
    Event entity = service.getById(id);
    if (entity == null) {
      return new EventResponse().error(404, "Event not found");
    }
    return new EventResponse().success( service.update(entity, updates) );
  }

  @ApiOperation(value = "Delete event")
  @DeleteMapping("/events/{id}")
  public EventResponse delete(@PathVariable Long id)
  {
    Event entity = service.getById(id);
    if (entity == null) {
      return new EventResponse().error(404, "Event not found");
    }
    service.delete(entity);
    return new EventResponse().success();
  }

  private JPAQuery generateGetQuery(
    User user,
    List<Long> id,
    String ownerId,
    Long from,
    Long to,
    Long createdFrom,
    Long createdTo,
    Long updatedFrom,
    Long updatedTo
  ) {
    QEvent qEvent = QEvent.event;
    JPAQuery query = new JPAQuery(entityManager);
    query.from(qEvent);
    BooleanExpression where = null;

    if (id.size() > 0) {
      where = qEvent.id.in(id).and(where);
    }

    String userId = user.getId();
    if (ownerId != null && ownerId != userId) {
      QPermission qPermission = QPermission.permission;
      BooleanExpression hasPermission = permissionService.getHasPermissionQuery(userId, "READ_EVENT")
          .and(qEvent.id.stringValue().eq(qPermission.entityId).or(qPermission.entityId.eq(ownerId)));
      query.innerJoin(qPermission).on(hasPermission);
      where = qEvent.ownerId.eq(ownerId).and(where);
    } else {
      where = qEvent.ownerId.eq(userId).and(where);
    }

    if (createdFrom != null) {
      where = qEvent.createdAt.goe(new Date(createdFrom)).and(where);
    }

    if (createdTo != null) {
      where = qEvent.createdAt.loe(new Date(createdTo)).and(where);
    }

    if (updatedFrom != null) {
      where = qEvent.updatedAt.goe(new Date(updatedFrom)).and(where);
    }

    if (updatedTo != null) {
      where = qEvent.updatedAt.loe(new Date(updatedTo)).and(where);
    }

    if (from != null) {
      where = qEvent.patterns.any().endedAt.goe(new Date(from)).and(where);
    }

    if (to != null) {
      where = qEvent.patterns.any().startedAt.loe(new Date(to)).and(where);
    }

    query.where(where);
    return query;
  }
}