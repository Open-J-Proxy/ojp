# DBeaver

This guide shows how to use the OJP JDBC driver in DBeaver.

It is written for the common case:

- you already have an OJP server running
- you already have a database working behind OJP
- you want to connect to that database from DBeaver

---

## Before you start

You need:

1. **DBeaver** installed
2. **A running OJP server**
3. **The backend JDBC URL** for your database
4. **The OJP server address and port**

You will also need one extra library because DBeaver does not automatically provide it:

- `org.slf4j:slf4j-api`

Optional:

- `org.slf4j:slf4j-nop` if you want silent logging
- `org.slf4j:slf4j-simple` if you want simple console logging

> For most DBeaver users, adding `slf4j-api` is enough.

---

## Step 1: Open the driver manager

In DBeaver:

1. Click **Database**
2. Click **Driver Manager**
3. Click **New**

---

## Step 2: Create the OJP driver

Fill the basic driver information:

- **Driver Name:** `OJP`
- **Class Name:** `org.openjproxy.jdbc.Driver`

You can choose any display name you like, but `OJP` is a good simple choice.

---

## Step 3: Add the OJP JDBC driver library

Open the **Libraries** tab.

Add this Maven artifact:

```text
org.openjproxy:ojp-jdbc-driver:<latest-version>
```

Replace `<latest-version>` with the latest OJP release version you want to use.

---

## Step 4: Add the missing SLF4J library

Still in the **Libraries** tab, add this Maven artifact too:

```text
org.slf4j:slf4j-api:<latest-2.0.x-version>
```

Why this is needed:

- OJP uses SLF4J for logging
- the OJP JDBC driver intentionally does not bundle `slf4j-api`
- DBeaver does not automatically add it for this driver

Without this library, the OJP driver may fail to load.

---

## Step 5: Optional logging choice

If you want, add one of these too:

### Option A: No logs

```text
org.slf4j:slf4j-nop:<same-2.0.x-version>
```

This disables SLF4J output.

### Option B: Simple logs

```text
org.slf4j:slf4j-simple:<same-2.0.x-version>
```

This gives basic logging output.

> If you are not sure, start with only `slf4j-api`. Add one of the optional logging libraries later if needed.

---

## Step 6: Create the connection

Now create a new database connection in DBeaver using the OJP driver you just created.

Use an OJP JDBC URL in this format:

```text
jdbc:ojp[<ojp-host>:<ojp-port>]_<real-jdbc-url>
```

Example for Oracle:

```text
jdbc:ojp[localhost:1059]_jdbc:oracle:thin:@//localhost:1521/FREEPDB1
```

Example for PostgreSQL:

```text
jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb
```

Then enter:

- your **database username**
- your **database password**

These are the credentials for the real database behind OJP.

---

## Step 7: Test the connection

Click **Test Connection**.

If everything is correct, DBeaver should connect through OJP to your database.

---

## Troubleshooting

### Error: `org/slf4j/...` class not found

Cause:

- `slf4j-api` was not added to the DBeaver driver libraries

Fix:

- add `org.slf4j:slf4j-api` with a 2.0.x version

---

### Error: driver loads but connection fails

Check:

1. Is the OJP server running?
2. Is the OJP host and port correct?
3. Is the real JDBC URL correct?
4. Can the OJP server reach the target database?
5. Is the correct backend JDBC driver available to the OJP server?

Remember:

- DBeaver uses the OJP JDBC driver on the client side
- the real database JDBC driver must be available on the **OJP server side**

---

## Important note about pooling

DBeaver is a client tool, not an application server.

The main OJP rule still applies:

- avoid adding another connection pool in front of OJP

For normal DBeaver usage, this is usually not a problem because you are just creating a direct JDBC connection from the tool.

---

## Summary

To use OJP in DBeaver:

1. Create a custom driver
2. Add `org.openjproxy:ojp-jdbc-driver`
3. Add `org.slf4j:slf4j-api`
4. Use an OJP JDBC URL
5. Test the connection

That is all you need in the common case.
