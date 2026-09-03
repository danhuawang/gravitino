/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import com.datastrato.gravitino.scim.storage.po.ScimUserMetaPO;
import java.util.List;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for {@code scim_user_meta}. */
public interface ScimUserMetaMapper {
  String TABLE_NAME = "scim_user_meta";

  @InsertProvider(type = ScimUserMetaSQLProviderFactory.class, method = "insert")
  void insert(@Param("userMeta") ScimUserMetaPO userMeta);

  @SelectProvider(type = ScimUserMetaSQLProviderFactory.class, method = "selectByExternalId")
  ScimUserMetaPO selectByExternalId(@Param("externalId") String externalId);

  @SelectProvider(type = ScimUserMetaSQLProviderFactory.class, method = "selectByUserName")
  ScimUserMetaPO selectByUserName(@Param("userName") String userName);

  /**
   * Selects an active user by case-insensitive {@code user_name}.
   *
   * @param userName user name to match ignoring case
   * @return matching row, or {@code null} when absent
   */
  @SelectProvider(
      type = ScimUserMetaSQLProviderFactory.class,
      method = "selectByUserNameIgnoreCase")
  ScimUserMetaPO selectByUserNameIgnoreCase(@Param("userName") String userName);

  /**
   * Selects active users whose {@code external_id} is in {@code externalIds}.
   *
   * @param externalIds SCIM resource ids; empty or {@code null} yields no rows
   * @return matching rows
   */
  @SelectProvider(type = ScimUserMetaSQLProviderFactory.class, method = "selectByExternalIds")
  List<ScimUserMetaPO> selectByExternalIds(@Param("externalIds") List<String> externalIds);

  @SelectProvider(type = ScimUserMetaSQLProviderFactory.class, method = "selectByUserId")
  ScimUserMetaPO selectByUserId(@Param("userId") long userId);

  @SelectProvider(type = ScimUserMetaSQLProviderFactory.class, method = "listUsers")
  List<ScimUserMetaPO> listUsers(@Param("offset") int offset, @Param("limit") int limit);

  @SelectProvider(type = ScimUserMetaSQLProviderFactory.class, method = "countUsers")
  Long countUsers();

  @UpdateProvider(type = ScimUserMetaSQLProviderFactory.class, method = "updateEnabled")
  Integer updateEnabled(@Param("externalId") String externalId, @Param("enabled") boolean enabled);

  @UpdateProvider(type = ScimUserMetaSQLProviderFactory.class, method = "updateEnabledByUserId")
  Integer updateEnabledByUserId(@Param("userId") long userId, @Param("enabled") boolean enabled);

  @UpdateProvider(type = ScimUserMetaSQLProviderFactory.class, method = "updateExternalId")
  Integer updateExternalId(@Param("userId") long userId, @Param("externalId") String externalId);

  @UpdateProvider(type = ScimUserMetaSQLProviderFactory.class, method = "softDeleteByUserId")
  Integer softDeleteByUserId(@Param("userId") long userId);

  @UpdateProvider(type = ScimUserMetaSQLProviderFactory.class, method = "softDeleteByExternalId")
  Integer softDeleteByExternalId(@Param("externalId") String externalId);

  @DeleteProvider(type = ScimUserMetaSQLProviderFactory.class, method = "deleteByLegacyTimeline")
  Integer deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit);
}
