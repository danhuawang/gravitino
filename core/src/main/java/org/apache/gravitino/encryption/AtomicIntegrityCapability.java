/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.encryption;

/**
 * Whether a loaded catalog can publish an Iceberg metadata pointer and its integrity hash in one
 * atomic commit. The value has a single source: the initialized catalog itself.
 */
public enum AtomicIntegrityCapability {
  /** The catalog commits the metadata pointer and the integrity hash atomically. */
  SUPPORTED,

  /** The catalog cannot commit the pointer and the hash atomically. */
  UNSUPPORTED;

  /**
   * Returns the capability a catalog-reported boolean stands for.
   *
   * @param supported whether the loaded catalog reports atomic metadata integrity
   * @return {@link #SUPPORTED} when {@code supported}, otherwise {@link #UNSUPPORTED}
   */
  public static AtomicIntegrityCapability of(boolean supported) {
    return supported ? SUPPORTED : UNSUPPORTED;
  }

  /**
   * Returns whether governed encryption has an atomic metadata-integrity implementation here.
   *
   * @return {@code true} only for {@link #SUPPORTED}
   */
  public boolean supported() {
    return this == SUPPORTED;
  }
}
