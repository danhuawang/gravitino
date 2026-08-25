<!--
Copyright 2026 Datastrato Pvt Ltd.
This software is licensed under the Apache License version 2.
-->

# Set Up and Upgrade OpenSearch Index Templates

This page covers two cases:

- New install: OpenSearch has no Gravitino templates.
- Upgrade: OpenSearch already has Gravitino templates.

`v1` and `v2` are Gravitino template versions. They are not OpenSearch product versions. For
example, OpenSearch 2.17.1 can use the Gravitino `v2` templates.

## Template, index, and alias

These three items are different:

- A template is a set of rules. OpenSearch uses it when it creates an index.
- An index holds a copy of Gravitino data for search.
- An alias is a fixed name that points to the index in use.

Installing a new template does not change an old index. Run `rebuild` if old indexes need the new
rules.

## Commands

| Command             | When to use it                        | What it does                                  |
|---------------------|---------------------------------------|-----------------------------------------------|
| `init [version]`    | No Gravitino template exists          | Creates one full set of templates             |
| `upgrade <version>` | Gravitino templates already exist     | Creates the new set, then removes the old set |
| `rebuild`           | The new Gravitino server is running   | Creates new indexes and moves the aliases     |
| `version`           | Before and after a change             | Shows the template version for each type      |
| `show`              | After a setup or an upgrade           | Shows aliases, indexes, and templates         |

`init` and `upgrade` change templates only. `rebuild` changes indexes and aliases only.

## Pick a case

| Case                                | Before the server starts | After the server starts |
|-------------------------------------|--------------------------|-------------------------|
| New Gravitino and new OpenSearch    | Run `init v2`            | No rebuild              |
| Old Gravitino data, new OpenSearch  | Run `init v2`            | Run `rebuild`           |
| Upgrade from `v1` to `v2`           | Run `upgrade v2`         | Run `rebuild`           |
| Restart a working `v2` server       | No template command      | No rebuild              |
| Some `v2` templates are missing     | Run `upgrade v2` again   | Start after all exist   |

The commands below use `v2` on purpose. This keeps the result the same after `v3` is added.

## New install

Use these steps when OpenSearch has no Gravitino templates.

1. Set the OpenSearch URL, user, and password in `conf/gravitino.conf`.
2. Install `jq` on the machine that runs `index.sh`.
3. Use the new Gravitino package to create the `v2` templates:

   ```bash
   export GRAVITINO_HOME=/opt/gravitino
   bin/index.sh init v2
   ```

4. Check the result. Every type must show `v2`:

   ```bash
   bin/index.sh version
   ```

5. Start Gravitino.

If the Gravitino metadata database is also new, stop here. New data will be added to OpenSearch as
users create it.

If the Gravitino metadata database already has data, fill the new OpenSearch indexes after the
server starts:

```bash
bin/index.sh rebuild --gravitino_uri=http://127.0.0.1:8090
```

Run `init` as a clear step in your install script. `bin/gravitino.sh start` tries to run `init`, but
`bin/gravitino.sh run` does not. The Docker entrypoint uses `run`. A Docker or Kubernetes install
must run `init v2` before it starts the Gravitino container.

Do not use `init` when templates already exist. It will stop and tell you to use `upgrade`.

## Upgrade from v1 to v2

`v2` adds templates for User, Group, Role, Function, View, Tag, and Policy. It also adds the
`policy_names` field to the types that were already in `v1`.

The upgrade has two parts:

1. `upgrade v2` installs the new templates.
2. `rebuild` copies Gravitino data into new indexes that use the `v2` templates.

Follow these steps:

1. Use the `index.sh` file from the new Gravitino package.
2. Stop the old Gravitino server. Keep OpenSearch running. Do not delete its indexes.
3. Stop users and jobs from changing Gravitino data during the upgrade.
4. Check the current version:

   ```bash
   bin/index.sh version
   ```

5. Install the `v2` templates. Do not run `init` here:

   ```bash
   bin/index.sh upgrade v2
   ```

6. Check again. Every type must now show `v2`:

   ```bash
   bin/index.sh version
   ```

7. Start the new Gravitino server.
8. Rebuild all search indexes:

   ```bash
   bin/index.sh rebuild --gravitino_uri=http://127.0.0.1:8090
   ```

9. Check aliases, indexes, and document counts. Also try a few searches:

   ```bash
   bin/index.sh show
   ```

10. Let users and jobs change data again.

The `upgrade` command creates all `v2` templates before it removes the `v1` templates. It does not
delete old indexes or move aliases. The `rebuild` command creates the new indexes, copies the data,
moves the aliases, and then removes the old indexes.

If `GRAVITINO_HOME` points to the new package, `index.sh` reads the OpenSearch URL and password from
`conf/gravitino.conf`. You can also pass `--opensearch_uri`, `--username`, and `--password` on the
command line.

## Restart without an upgrade

If all templates are already on `v2` and search works, a restart needs no `init`, no `upgrade`, and
no `rebuild`.

`bin/gravitino.sh start` tries to run `init`. You may see a message that templates already exist.
This is normal when every needed template shows `v2`. If you see `v1`, stop the new server and run
`upgrade v2` first.

If an earlier `upgrade v2` stopped after creating only some templates, fix the problem and run
`upgrade v2` again. Templates that already exist are kept. Missing templates are added.

## If a command fails

- If `upgrade` fails before it creates a `v2` template, the `v1` templates stay in use. Fix the
  error and run the command again.
- If `upgrade` creates only some `v2` templates, do not start the new server. Fix the error and run
  `upgrade v2` again.
- If `version` still shows `v1`, do not start the new server. The server may fail because a template
  is missing.
- If `rebuild` fails, fix the error and run it again. Gravitino's metadata database is the main copy
  of the data.
- Do not change files inside a template version after it is released. Add a new version instead.

## Future v3 and v4 versions

Use the same rules for later versions:

1. Add a new folder, such as `bin/opensearch/v3/`.
2. Put every template needed by that Gravitino version in the folder.
3. Do not make `v3` read files from `v2`.
4. Test a new install, an upgrade from the last version, a failed upgrade and retry, and a rebuild.
5. For a new install, run `init v3`.
6. For an old install, run `upgrade v3`, start the new server, and then run `rebuild`.

The script can install a full new set without installing each version in between. This does not mean
every jump is safe. Each release must say which old versions can upgrade to it, and those paths must
have tests.
