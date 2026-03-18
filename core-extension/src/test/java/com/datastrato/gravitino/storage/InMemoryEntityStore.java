/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.storage;

import com.google.common.collect.Maps;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.EntityAlreadyExistsException;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.HasIdentifier;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.utils.Executable;

public class InMemoryEntityStore implements EntityStore {
  private final Map<NameIdentifier, Entity> entityMap;
  private final Lock lock;

  public InMemoryEntityStore() {
    this.entityMap = Maps.newConcurrentMap();
    this.lock = new ReentrantLock();
  }

  public void clear() {
    entityMap.clear();
  }

  @Override
  public void initialize(Config config) throws RuntimeException {}

  @Override
  public <E extends Entity & HasIdentifier> List<E> list(
      Namespace namespace, Class<E> cl, Entity.EntityType entityType) throws IOException {
    // assert the namespace exists
    NameIdentifier parentIdent = NameIdentifier.of(namespace.levels());
    if (!entityMap.containsKey(parentIdent)) {
      throw new NoSuchEntityException("Namespace %s does not exist", namespace);
    }
    return entityMap.entrySet().stream()
        .filter(e -> e.getKey().namespace().equals(namespace))
        .map(entry -> (E) entry.getValue())
        .collect(Collectors.toList());
  }

  @Override
  public boolean exists(NameIdentifier nameIdentifier, Entity.EntityType type) throws IOException {
    return entityMap.containsKey(nameIdentifier);
  }

  @Override
  public <E extends Entity & HasIdentifier> void put(E e, boolean overwritten)
      throws IOException, EntityAlreadyExistsException {
    NameIdentifier ident = e.nameIdentifier();
    if (overwritten) {
      entityMap.put(ident, e);
    } else {
      executeInTransaction(
          () -> {
            if (exists(e.nameIdentifier(), e.type())) {
              throw new EntityAlreadyExistsException("Entity %s already exists", ident);
            }
            entityMap.put(ident, e);
            return null;
          });
    }
  }

  @Override
  public <E extends Entity & HasIdentifier> E update(
      NameIdentifier ident, Class<E> type, Entity.EntityType entityType, Function<E, E> updater)
      throws IOException, NoSuchEntityException {
    return executeInTransaction(
        () -> {
          E e = (E) entityMap.get(ident);
          if (e == null) {
            throw new NoSuchEntityException("Entity %s does not exist", ident);
          }

          E newE = updater.apply(e);
          NameIdentifier newIdent = NameIdentifier.of(newE.namespace(), newE.name());
          if (!newIdent.equals(ident)) {
            delete(ident, entityType);
          }
          entityMap.put(newIdent, newE);
          return newE;
        });
  }

  @Override
  public <E extends Entity & HasIdentifier> E get(
      NameIdentifier ident, Entity.EntityType entityType, Class<E> cl)
      throws NoSuchEntityException, IOException {
    E e = (E) entityMap.get(ident);
    if (e == null) {
      throw new NoSuchEntityException("Entity %s does not exist", ident);
    }

    return e;
  }

  @Override
  public <E extends Entity & HasIdentifier> List<E> batchGet(
      List<NameIdentifier> idents, Entity.EntityType entityType, Class<E> clazz) {
    return idents.stream()
        .map(ident -> (E) entityMap.get(ident))
        .filter(e -> e != null)
        .collect(Collectors.toList());
  }

  @Override
  public boolean delete(NameIdentifier ident, Entity.EntityType entityType, boolean cascade)
      throws IOException {
    Entity prev = entityMap.remove(ident);
    return prev != null;
  }

  @Override
  public <R, E extends Exception> R executeInTransaction(Executable<R, E> executable)
      throws E, IOException {
    try {
      lock.lock();
      return executable.execute();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() throws IOException {
    entityMap.clear();
  }

  @Override
  public int batchDelete(List<Pair<NameIdentifier, EntityType>> entitiesToDelete, boolean cascade)
      throws IOException {
    for (Pair<NameIdentifier, EntityType> pair : entitiesToDelete) {
      entityMap.remove(pair.getLeft());
    }
    return 0;
  }

  @Override
  public <E extends Entity & HasIdentifier> void batchPut(List<E> entities, boolean overwritten)
      throws IOException, EntityAlreadyExistsException {
    executeInTransaction(
        () -> {
          for (E e : entities) {
            put(e, overwritten);
          }
          return null;
        });
  }
}
