/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Preconditions;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.gravitino.Entity;
import org.apache.gravitino.Entity.EntityType;

@Getter
@AllArgsConstructor
@JsonDeserialize(builder = SearchEntitiesDTO.Builder.class)
public class SearchEntitiesDTO {

  private final int totalSize;

  private final EntityType type;

  private final List<? extends SearchEntityDTO> entities;

  /**
   * Whether {@link #totalSize} stopped at the authorization scan limit. When {@code true} the
   * metalake holds more matches of this type than a single query authorizes, so {@code totalSize}
   * is a lower bound rather than the exact number of visible matches.
   */
  private final boolean totalSizeTruncated;

  public SearchEntitiesDTO(
      int totalSize, EntityType type, List<? extends SearchEntityDTO> entities) {
    this(totalSize, type, entities, false);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private int totalSize;
    private EntityType type;
    private List<? extends SearchEntityDTO> entities;
    private boolean totalSizeTruncated;

    public Builder withTotalSize(int totalSize) {
      this.totalSize = totalSize;
      return this;
    }

    public Builder withType(EntityType type) {
      this.type = type;
      return this;
    }

    public Builder withEntities(List<? extends SearchEntityDTO> entities) {
      this.entities = entities;
      return this;
    }

    public Builder withTotalSizeTruncated(boolean totalSizeTruncated) {
      this.totalSizeTruncated = totalSizeTruncated;
      return this;
    }

    public SearchEntitiesDTO build() {
      Preconditions.checkArgument(totalSize >= 0, "Total size must be non-negative");
      Preconditions.checkArgument(type != null, "Type must not be null");
      Preconditions.checkArgument(entities != null, "Entities must not be null");
      return new SearchEntitiesDTO(totalSize, type, entities, totalSizeTruncated);
    }

    public static SearchEntitiesDTO getSearchEntitiesDTOByType(
        List<SearchEntitiesDTO> dtos, Entity.EntityType type) {
      return dtos.stream().filter(dto -> dto.getType() == type).findFirst().orElse(null);
    }
  }
}
