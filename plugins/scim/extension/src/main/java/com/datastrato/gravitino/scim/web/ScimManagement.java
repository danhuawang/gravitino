/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import javax.ws.rs.NameBinding;

/**
 * Binds SCIM admin REST resources to {@link
 * com.datastrato.gravitino.scim.web.rest.ScimAuthorizationFilter}.
 */
@NameBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ScimManagement {}
