## Run postgres on docker

### Preconditions
Have docker installed in your machine.

### Run command

> docker run --name ojp-postgres -e POSTGRES_USER=testuser -e POSTGRES_PASSWORD=testpassword -e POSTGRES_DB=defaultdb -d -p 5432:5432 postgres:17 

#### docker run
Tells Docker to run a new container.

#### --name ojp-postgres
Assigns the name ojp-postgres to the container (makes it easier to manage and reference).

#### -e POSTGRES_USER=testuser
Sets the database username to 'testuser'.

#### -e POSTGRES_PASSWORD=testpassword
Sets the database user password to 'testpassword'.

#### -e POSTGRES_DB=defaultdb
Creates a default database named 'defaultdb' on startup.

#### -d
Runs the container in detached mode (in the background).

#### -p 5432:5432
Maps port 5432 of your host to port 5432 of the container (PostgreSQL’s default port), allowing local access.

#### postgres:17
Specifies the Docker image to use (in this case, the official PostgreSQL image version 17 from Docker Hub).

---

## Run mysql on docker

### Preconditions
Have docker installed in your machine.

### Run command

> docker run --name ojp-mysql -e MYSQL_ROOT_PASSWORD=testpassword -e MYSQL_DATABASE=defaultdb -e MYSQL_USER=testuser -e MYSQL_PASSWORD=testpassword -d -p 3306:3306 mysql:8.0

#### docker run
Tells Docker to run a new container.

#### --name ojp-mysql
Assigns the name ojp-mysql to the container (makes it easier to manage and reference).

#### -e MYSQL_ROOT_PASSWORD=testpassword
Sets the root password for MySQL (required by the image).

#### -e MYSQL_DATABASE=defaultdb
Creates a default database named 'defaultdb' on startup.

#### -e MYSQL_USER=testuser
Creates a new user named 'testuser'.

#### -e MYSQL_PASSWORD=testpassword
Sets the password for 'testuser'.

#### -d
Runs the container in detached mode (in the background).

#### -p 3307:3306
Maps port 3307 of your host to port 3306 of the container (**3307** to not conflict with MySQL’s default port), allowing local access.

#### mysql:8.0
Specifies the Docker image to use (in this case, the official MySQL image version 8.0 from Docker Hub).


## Run MariaDB on Docker

### Preconditions
Have Docker installed on your machine.

### Run Command

> docker run --name ojp-mariadb -e MARIADB_ROOT_PASSWORD=testpassword -e MARIADB_DATABASE=defaultdb -e MARIADB_USER=testuser -e MARIADB_PASSWORD=testpassword -d -p 3307:3306 mariadb:10.11

#### docker run
Tells Docker to run a new container.

#### --name ojp-mariadb
Assigns the name `ojp-mariadb` to the container, making it easier to manage and reference.

#### -e MARIADB_ROOT_PASSWORD=testpassword
Sets the root password for MariaDB (required by the image).

#### -e MARIADB_DATABASE=defaultdb
Creates a default database named `defaultdb` on startup.

#### -e MARIADB_USER=testuser
Creates a new user named `testuser`.

#### -e MARIADB_PASSWORD=testpassword
Sets the password for the `testuser`.

#### -d
Runs the container in detached mode (in the background).

#### -p 3307:3306
Maps port 3307 of your host to port 3306 of the container (MariaDB’s default port), allowing local access.

#### mariadb:10.11
Specifies the Docker image to use (in this case, the 10.11 official MariaDB image from Docker Hub).

---

## Run Oracle on Docker

### Preconditions
Have Docker installed on your machine.

### Run Command

> docker run --name ojp-oracle -e ORACLE_PASSWORD=testpassword -e APP_USER=testuser -e APP_USER_PASSWORD=testpassword -d -p 1521:1521 gvenzl/oracle-xe:21-slim

#### docker run
Tells Docker to run a new container.

#### --name ojp-oracle
Assigns the name `ojp-oracle` to the container, making it easier to manage and reference.

#### -e ORACLE_PASSWORD=testpassword
Sets the password for the SYS and SYSTEM users (required by the image).

#### -e APP_USER=testuser
Creates a new application user named `testuser`.

#### -e APP_USER_PASSWORD=testpassword
Sets the password for the `testuser`.

#### -d
Runs the container in detached mode (in the background).

#### -p 1521:1521
Maps port 1521 of your host to port 1521 of the container (Oracle's default port), allowing local access.

#### gvenzl/oracle-xe:21-slim
Specifies the Docker image to use (in this case, the community Oracle XE 21c image from Docker Hub). This is a lightweight, license-free Oracle Express Edition suitable for development and testing.