# Spring boot

To integrate OJP into your Spring Boot project follow the steps:

## 1 Add the maven dependency to your project.

         <dependency>
             <groupId>org.openjproxy</groupId>
             <artifactId>ojp-jdbc-driver</artifactId>
             <version>[TBD]</version>
         </dependency>

## 2 Remove the local connection pool
Spring boot by default comes with HikariCP connection pool, as OJP replaces completely the connection pool, remove it in the pom.xml as follows.

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
            <exclusions>
                <!--When using OJP proxied connection pool the local pool needs to be removed -->
                <exclusion>
                    <groupId>com.zaxxer</groupId>
                    <artifactId>HikariCP</artifactId>
                </exclusion>
            </exclusions>
        </dependency>

## 3 Change your connection URL
In your application.properties(or yaml) file, update your database connection URL, and add the OJP jdbc driver class as in the following example:
>  spring.datasource.url=jdbc:ojp[localhost:1059]_h2:~/test
> 
> spring.datasource.driver-class-name=org.openjproxy.jdbc.Driver
>
> #Sets the datasource to not use hikari CP connection pool and open single connections instead
> spring.datasource.type=org.springframework.jdbc.datasource.SimpleDriverDataSource
> 

The example above is for h2 but it is similar to any other database, you just need to add the "ojp[host:port]_" pattern immediately after "jdbc:". "[host:port]" indicates the host and port you have your OJP proxy server running.