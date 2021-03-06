package com.skillmasters.server.service;

import com.querydsl.core.types.dsl.BooleanExpression;
import org.springframework.stereotype.Service;

import com.skillmasters.server.repository.PermissionRepository;
import com.skillmasters.server.model.*;

@Service
public class PermissionService extends EntityService<PermissionRepository, Permission, Long>
{
  private final QPermission qPermission = QPermission.permission;

  public PermissionService()
  {
    super(Permission.class, "PERMISSION");
  }

  public Permission generate(String userId, String action, IEntity entity)
  {
    Permission permission = new Permission();
    permission.setName(action + "_" + entity.getEntityName());
    permission.setUserId(userId);
    permission.setOwnerId(getCurrentUser().getId());
    permission.setEntityId(entity.getId().toString());
    return permission;
  }

  public Permission generate(String userId, String action, String entity)
  {
    String ownerId = getCurrentUser().getId();
    Permission permission = new Permission();
    permission.setName(action + "_" + entity);
    permission.setUserId(userId);
    permission.setOwnerId(ownerId);
    permission.setEntityId(ownerId);
    return permission;
  }

  public Permission grant(String userId, String action, String entity)
  {
    Permission permission = generate(userId, action, entity);
    if (repository.exists(existsExpression(permission))) {
      return null;
    }
    return repository.save(permission);
  }

  public Permission grant(String userId, String action, IEntity entity)
  {
    Permission permission = generate(userId, action, entity);
    if (repository.exists(existsExpression(permission))) {
      return null;
    }
    return repository.save(permission);
  }

  public Permission grant(Permission permission)
  {
    if (repository.exists(existsExpression(permission))) {
      return null;
    }
    return repository.save(permission);
  }

  public boolean hasPermission(User user, String action, IEntity entity)
  {
    action += "_" + entity.getEntityName();
    return repository.exists(
        getHasPermissionQuery(user.getId(), action)
        .and(qPermission.entityId.in(entity.getOwnerId(), entity.getId().toString()))
      );
  }

  public BooleanExpression getHasPermissionQuery(String userId, String perm)
  {
    return qPermission.userId.eq(userId).and(qPermission.name.eq(perm));
  }

  public void deleteByEntity(IEntity entity)
  {
    if (entity instanceof Permission) {
      return;
    }

    if (entity instanceof Event) {
      Event event = (Event) entity;
      if (event.getTasks() != null) {
        for (Task task : event.getTasks()) {
          repository.deleteEntityPermissions(task.getId().toString(), task.getOwnerId(), task.getEntityName());
        }
      }
      if (event.getPatterns() != null) {
        for (EventPattern pattern : event.getPatterns()) {
          repository.deleteEntityPermissions(pattern.getId().toString(), pattern.getOwnerId(), pattern.getEntityName());
        }
      }
    }

    repository.deleteEntityPermissions(entity.getId().toString(), entity.getOwnerId(), entity.getEntityName());
  }

  private BooleanExpression existsExpression(Permission perm)
  {
    return qPermission.entityId.eq(perm.getEntityId())
        .and(qPermission.userId.eq(perm.getUserId()))
        .and(qPermission.name.eq(perm.getName()));
  }
}