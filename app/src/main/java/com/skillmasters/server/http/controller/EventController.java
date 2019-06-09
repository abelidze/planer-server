package com.skillmasters.server.http.controller;

import java.util.TimeZone;
import java.util.Map;
import java.util.Date;
import java.util.Arrays;
import java.util.List;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.lang.reflect.Field;

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

import com.google.common.base.CaseFormat;
import com.google.common.collect.Lists;
import com.querydsl.core.types.dsl.BooleanExpression;

import com.skillmasters.server.misc.OffsetPageRequest;
import com.skillmasters.server.http.response.EventResponse;
import com.skillmasters.server.repository.EventRepository;
import com.skillmasters.server.model.User;
import com.skillmasters.server.model.Event;
import com.skillmasters.server.model.Event.EventBuilder;
import com.skillmasters.server.model.EventPattern;
import com.skillmasters.server.model.QEvent;

@RestController
@RequestMapping("/api/v1")
@Api(tags="Events", description="planer's events")
public class EventController
{
  @Autowired
  EventRepository repository;

  @Autowired
  RecurrenceRuleScribe scribe;

  @Autowired
  ParseContext context;

  @ApiOperation(value = "Get a list of available events", response = EventResponse.class)
  @GetMapping("/events")
  public EventResponse retrieve(
    @AuthenticationPrincipal User user,
    @RequestParam(value="id", defaultValue="") List<Long> id,
    @RequestParam(value="offset", defaultValue="0") long offset,
    @RequestParam(value="count", defaultValue="100") int count,
    @RequestParam(value="from", required=false) Long from,
    @RequestParam(value="to", required=false) Long to,
    @RequestParam(value="created_from", required=false) Long createdFrom,
    @RequestParam(value="created_to", required=false) Long createdTo,
    @RequestParam(value="updated_from", required=false) Long updatedFrom,
    @RequestParam(value="updated_to", required=false) Long updatedTo
  ) {
    QEvent qEvent = QEvent.event;
    BooleanExpression query = qEvent.ownerId.eq(user.getId());

    if (id.size() > 0) {
      query = qEvent.id.in(id).and(query);
    }

    if (createdFrom != null) {
      query = qEvent.createdAt.goe(new Date(createdFrom)).and(query);
    }

    if (createdTo != null) {
      query = qEvent.createdAt.loe(new Date(createdTo)).and(query);
    }

    if (updatedFrom != null) {
      query = qEvent.updatedAt.goe(new Date(updatedFrom)).and(query);
    }

    if (updatedTo != null) {
      query = qEvent.updatedAt.loe(new Date(updatedTo)).and(query);
    }

    if (from == null && to == null) {
      return new EventResponse().success( repository.findAll(query, new OffsetPageRequest(offset, count)) );
    }

    Date fromDate;
    if (from == null) {
      fromDate = new Date(0);
    } else {
      fromDate = new Date(from);
      query = qEvent.patterns.any().endedAt.goe(fromDate).and(query);
    }

    Date toDate;
    if (to == null) {
      toDate = new Date(Long.MAX_VALUE);
    } else {
      toDate = new Date(to);
      query = qEvent.patterns.any().startedAt.loe(toDate).and(query);
    }

    Date eventDate;
    TimeZone utcTimezone = TimeZone.getTimeZone("UTC");
    TimeZone timezone = utcTimezone;
    DateFormat df = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
    df.setTimeZone(utcTimezone);

    List<Event> result = Lists.newArrayList();
    Iterable<Event> events = repository.findAll(query);
    for (Event event : events) {
      for (EventPattern pattern : event.getPatterns()) {
        EventBuilder eventBuilder = event.toBuilder();
        Date start = pattern.getStartedAt();
        Date end = pattern.getEndedAt();

        String rruleStr = pattern.getRrule();
        if (rruleStr == null) {
          eventBuilder.startedAt(start);
          eventBuilder.endedAt(end);
          result.add(eventBuilder.build());
        } else {
          end = end.before(toDate) ? end : toDate;
          rruleStr += ";UNTIL=" + df.format(end);

          if (Strings.isNullOrEmpty(pattern.getTimezone())) {
            timezone = utcTimezone;
          } else {
            timezone = TimeZone.getTimeZone(pattern.getTimezone());
          }

          RecurrenceRule rrule = scribe.parseText(rruleStr, null, new ICalParameters(), context);
          DateIterator dateIt = rrule.getDateIterator(start, timezone);

          if (start.before(fromDate)) {
            dateIt.advanceTo(fromDate);
          }

          for (int i = 0; i < 100 && dateIt.hasNext(); ++i) {
            eventDate = dateIt.next();
            eventBuilder.startedAt(eventDate);
            eventBuilder.endedAt(new Date(eventDate.getTime() + pattern.getDuration()));
            result.add(eventBuilder.build());
          }
        }
      }
    }
    return new EventResponse().success(result);
  }

  @ApiOperation(value = "Create event", response = EventResponse.class)
  @PostMapping("/events")
  public EventResponse create(@AuthenticationPrincipal User user, @RequestBody Event event)
  {
    event.setOwnerId(user.getId());
    return new EventResponse().success( Arrays.asList(repository.save(event)) );
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
  public EventResponse update(
    @AuthenticationPrincipal User user,
    @PathVariable Long id,
    @RequestBody Map<String, Object> updates
  ) {
    QEvent qEvent = QEvent.event;
    if (!repository.exists( qEvent.id.eq(id).and(qEvent.ownerId.eq(user.getId())) )) {
      return new EventResponse().error(404, "Event not found or you don't have access to it");
    }

    Event entity = repository.findById(id).get();
    updates.forEach((k, v) -> {
      String fieldName = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, k);
      Field field = ReflectionUtils.findField(Event.class, fieldName);
      if (field != null) {
        ReflectionUtils.makeAccessible(field);
        final Class<?> type = field.getType();
        System.out.println(type.getName());
        if (type.equals(Long.class)) {
          ReflectionUtils.setField(field, entity, ((Number) v).longValue());
        } else if (type.equals(Date.class)) {
          ReflectionUtils.setField(field, entity, new Date( ((Number) v).longValue() ));
        } else {
          ReflectionUtils.setField(field, entity, v);
        }
      }
    });
    return new EventResponse().success(Arrays.asList( repository.save(entity) ));
  }

  @ApiOperation(value = "Delete event")
  @DeleteMapping("/events/{id}")
  public EventResponse delete(@AuthenticationPrincipal User user, @PathVariable Long id)
  {
    QEvent qEvent = QEvent.event;
    if (!repository.exists( qEvent.id.eq(id).and(qEvent.ownerId.eq(user.getId())) )) {
      return new EventResponse().error(404, "Event not found or you don't have access to it");
    }
    repository.deleteById(id);
    return new EventResponse().ok("ok");
  }
}