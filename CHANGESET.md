# Pickleball date/time documentation update

This revision keeps date/time documentation as a normal documentation subpage. It does **not** add a date/time section to the repository's top-level `README.md`.

## Documentation changes

- Adds `docs/date-time-utilities.md` as a page listed from `docs/README.md`.
- Links the date/time page to the runnable consumer example:
  `maven-consumer-project/src/test/resources/features/date-time-utilities.feature`.
- Inserts the date/time page into the existing previous/home/next documentation chain between Mapping and Templating and Shared Configuration Files.
- Adds an **Executable examples** section to existing documentation pages with a clear matching consumer feature.
- Links `feature-status-notes.md` to the complete consumer feature directory because it describes the project as a whole rather than one feature.
- Updates the Maven consumer README so all example feature paths are clickable Markdown links.
- Removes the earlier generated `## Date and time expressions` section from the top-level README when the apply script finds it.

## Consumer additions retained

- `maven-consumer-project/src/test/resources/features/date-time-utilities.feature`
- `maven-consumer-project/src/test/resources/site/datetime.html`
- the local-site index and URL mapping updates
- `docs/examples/CALENDARS.date-time-example.yaml`

## Apply

```bash
python apply_datetime_update.py /path/to/ds-code-t/pickleball --calendar ExistingCalendarName
```

The script makes one-time `.datetime-update-backup` copies before modifying existing files. It is idempotent: running it again does not duplicate the example sections.
