/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.search.service.SearchService;
import com.google.common.collect.ImmutableMap;
import java.lang.reflect.Field;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.job.JobTemplate;
import org.apache.gravitino.job.JobTemplateChange;
import org.apache.gravitino.listener.api.event.job.AlterJobTemplateEvent;
import org.apache.gravitino.listener.api.event.job.DeleteJobTemplateEvent;
import org.apache.gravitino.listener.api.event.job.JobTemplateEvent;
import org.apache.gravitino.listener.api.event.job.RegisterJobTemplateEvent;
import org.apache.gravitino.listener.api.event.job.RunJobEvent;
import org.apache.gravitino.listener.api.info.JobInfo;
import org.apache.gravitino.meta.JobEntity;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.Test;

class TestJobTemplateEventHandler {
  @Test
  void testTemplateMutations() {
    SearchService service = mock(SearchService.class);
    JobTemplateEventHandler handler = new JobTemplateEventHandler(service);
    JobTemplate template = mock(JobTemplate.class);
    when(template.name()).thenReturn("template");
    handler.handleEvent(new RegisterJobTemplateEvent("user", "lake", template));
    verify(service)
        .synchronizeMetadata(
            NameIdentifierUtil.ofJobTemplate("lake", "template"), EntityType.JOB_TEMPLATE, false);
    when(template.name()).thenReturn("renamed");
    handler.handleEvent(
        new AlterJobTemplateEvent(
            "user",
            "lake",
            new JobTemplateChange[] {JobTemplateChange.rename("renamed")},
            template));
    verify(service)
        .synchronizeMetadata(
            NameIdentifierUtil.ofJobTemplate("lake", "renamed"), EntityType.JOB_TEMPLATE, false);
    handler.handleEvent(new DeleteJobTemplateEvent("user", "lake", "renamed", true));
    verify(service)
        .removeMetadata(
            NameIdentifierUtil.ofJobTemplate("lake", "renamed"), EntityType.JOB_TEMPLATE, false);
    verifyNoMoreInteractions(service);
  }

  @Test
  void testListenerRoutesTemplateEventsAndDropsJobEvents() throws Exception {
    // Only job templates are searchable. A job execution is a transient record identified by a
    // generated ID, carrying nothing a caller would search for that the template does not hold, so
    // its events must not reach the search service at all.
    DataDiscoveryListener listener = ListenerTestUtils.newListener();
    EventHandler handler = mock(EventHandler.class);
    Field field = DataDiscoveryListener.class.getDeclaredField("eventHandlers");
    field.setAccessible(true);
    field.set(listener, ImmutableMap.of(JobTemplateEvent.class, handler));
    JobEntity job = mock(JobEntity.class);
    when(job.name()).thenReturn("job-1");
    JobInfo info = JobInfo.fromJobEntity(job);
    RunJobEvent run = new RunJobEvent("user", "lake", "template", ImmutableMap.of(), info);
    DeleteJobTemplateEvent delete = new DeleteJobTemplateEvent("user", "lake", "template", true);

    listener.onPostEvent(run);
    listener.onPostEvent(delete);

    verify(handler).handleEvent(delete);
    verify(handler, never()).handleEvent(run);
  }
}
