package com.skillmasters.server.service;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

import com.google.common.hash.Hashing;
import org.springframework.stereotype.Service;
import com.skillmasters.server.model.Permission;

@Service
public class ShareService
{
  private final Map<String, CachedPermission> cache = new HashMap<>();
  private final long INVALIDATE_TIME = 3600000;

  private class CachedPermission
  {
    public long timestamp;
    public List<Permission> permissions;

    public CachedPermission(Permission permission)
    {
      this.permissions = Arrays.asList(permission);
      this.timestamp = new Date().getTime();
    }

    public CachedPermission(List<Permission> permissions)
    {
      this.permissions = permissions;
      this.timestamp = new Date().getTime();
    }
  }

  public String cachePermission(Permission permission)
  {
    String token = generateToken();
    cache.put(token, new CachedPermission(permission));
    return token;
  }

  public String cachePermissionList(List<Permission> permissions)
  {
    String token = generateToken();
    cache.put(token, new CachedPermission(permissions));
    return token;
  }

  public void revokeToken(String token)
  {
    this.cache.remove(token);
  }

  public List<Permission> validateToken(String token)
  {
    CachedPermission obj = this.cache.get(token);
    if (obj == null || new Date().getTime() - obj.timestamp > this.INVALIDATE_TIME) {
      revokeToken(token);
      return null;
    }
    return obj.permissions;
  }

  private String generateToken()
  {
    String uuid = UUID.randomUUID().toString();
    return Hashing.sha256().hashString(uuid, StandardCharsets.UTF_8).toString();
  }
}