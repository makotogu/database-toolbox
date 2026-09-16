Bundled JDBC components
=======================

These original Maven Central artifacts are packaged as resources, then loaded
using separate JDBC class loaders. They are not repackaged or relocated.
Original license texts and PostgreSQL embedded dependency notices are preserved
in this directory. Application usage does not require downloading these files.

MySQL Connector/J 9.7.0
  Coordinate: com.mysql:mysql-connector-j:9.7.0
  Source: https://github.com/mysql/mysql-connector-j/tree/9.7.0
  Source archive: https://github.com/mysql/mysql-connector-j/archive/refs/tags/9.7.0.tar.gz
  License: see mysql-connector-j-9.7.0-LICENSE.txt (GPL v2 and exceptions/notices).
  Non-optional dependency below is kept in the same isolated driver profile.

Protocol Buffers Java 4.31.1
  Coordinate: com.google.protobuf:protobuf-java:4.31.1
  Source: https://github.com/protocolbuffers/protobuf/tree/v31.1
  License: protobuf-java-4.31.1-LICENSE.txt

PostgreSQL JDBC 42.7.13
  Coordinate: org.postgresql:postgresql:42.7.13
  Source: https://github.com/pgjdbc/pgjdbc/tree/REL42.7.13
  License and dependency notices: postgresql-42.7.13/META-INF/

H2 2.2.224
  Coordinate: com.h2database:h2:2.2.224
  Source: https://github.com/h2database/h2database/tree/version-2.2.224
  License: h2-2.2.224-LICENSE.txt
  This release is retained for Java 8 compatibility; newer H2 lines require Java 11.

Version, driver class, filenames and SHA-256 are listed in bundled-drivers/catalog.json.
To update a driver, update the Maven copy artifact, catalog hashes and these notices
together, then verify the packaged JAR under Java 8.
