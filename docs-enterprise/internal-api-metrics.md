---
title: Internal API metrics
slug: /internal-api-metrics
keyword: metrics monitoring internal api web ui
license: "Copyright 2026 Datastrato Inc."
---

## Introduction

Apache Gravitino Enterprise serves the REST APIs used by the Web UI and the bundled tooling under
`/api/internal`. These endpoints are instrumented exactly like the public REST API, so the work an
operator does through the UI shows up in `/metrics` alongside the work done through the API.

Their operation names carry an `internal-` prefix. This page covers that prefix and how to query
the resulting metrics. For the general metrics mechanism, the exposition formats and the other
metric sources, see [Metrics](/metrics).

## Why the prefix exists

Several internal endpoints mirror a public one of the same name. Creating a catalog through the Web
UI and creating a catalog through the public REST API are two different code paths that would
otherwise report under one name, which makes them impossible to tell apart on a dashboard:

| Action                                                       | Operation name            |
|--------------------------------------------------------------|---------------------------|
| `POST /api/metalakes/{metalake}/catalogs`                    | `create-catalog`          |
| `POST /api/internal/with-tags/metalakes/{metalake}/catalogs` | `internal-create-catalog` |

The prefix also lets a dashboard select the whole internal surface in one expression, which is the
difference between reporting "the server is idle" and "the server is only being driven through the
UI".

The metric names themselves are literal strings on the `@Timed` and `@ResponseMetered` annotations;
they are not generated from the path at runtime. What keeps them in step with the path is
`TestRestApiMetricsInstrumentation`, which derives the expected prefix from `InternalApiPaths.PREFIX`
and fails the build when an endpoint is missing the annotations, is served under the internal path
without the prefix, or carries the prefix while being served outside it.

## Querying the metrics

Each instrumented endpoint produces one timer and a set of response meters:

- `@Timed` produces `<operation>.http-request-duration-seconds`
- `@ResponseMetered` produces `<operation>.1xx-responses` through `<operation>.5xx-responses`

They are registered when the server starts, not lazily on the first request, so an operation that
has never been called is present with a count of zero. An operation missing from `/metrics`
entirely means it is not instrumented, which is a bug.

List every internal operation the server exposes:

```shell
curl -sS http://127.0.0.1:8090/metrics \
  | jq -r '(.timers + .meters) | keys[]' \
  | grep 'gravitino-server\.internal-'
```

:::caution
The timer lives under `.timers` and the response meters live under `.meters`. A query that only
reads `.timers` shows latency but no success or error counts.
:::

In Prometheus format the operation name becomes the `operation` label:

```text
gravitino_server_2xx_responses_total{operation="internal-create-catalog",} 0.0
gravitino_server_4xx_responses_total{operation="internal-create-catalog",} 0.0
gravitino_server_5xx_responses_total{operation="internal-create-catalog",} 0.0
gravitino_server_http_request_duration_seconds_count{operation="internal-create-catalog",} 0.0
gravitino_server_http_request_duration_seconds{operation="internal-create-catalog",quantile="0.95",} 0.0
```

## Object creation through the Web UI

The Web UI creates an object and applies its tags in a single request, so each object type has its
own internal operation:

| Object          | Operation name             |
|-----------------|----------------------------|
| Catalog         | `internal-create-catalog`  |
| Schema          | `internal-create-schema`   |
| Table           | `internal-create-table`    |
| Fileset         | `internal-create-fileset`  |
| Topic           | `internal-create-topic`    |
| Model           | `internal-register-model`  |
| Connection test | `internal-test-connection` |

Error responses are counted the same way as successful ones. A creation that fails increments
`internal-create-catalog.4xx-responses` or `.5xx-responses`, which is what lets a failed UI action
reach the Errors panel on the Monitor page rather than disappearing silently.

## Scope

The prefix applies to resources served under `/api/internal`. The license endpoint (`/license`) and
the basic IdP endpoints (`/idp/users`, `/idp/groups`) are served outside that path and keep their
own names.
