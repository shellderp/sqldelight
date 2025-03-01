# Getting started with SQLDelight on Kotlin/Native

{% include 'common/index_gradle_database.md' %}

{% include 'common/index_schema.md' %}

=== "Kotlin"
    ```kotlin
    kotlin {
      // or sourceSets.iosMain, sourceSets.windowsMain, etc.
      sourceSets.nativeMain.dependencies {
        implementation("app.cash.sqldelight:native-driver:{{ versions.sqldelight }}")
      }
    }
    ```
=== "Groovy"
    ```groovy
    kotlin {
      // or sourceSets.iosMain, sourceSets.windowsMain, etc.
      sourceSets.nativeMain.dependencies {
        implementation "app.cash.sqldelight:native-driver:{{ versions.sqldelight }}"
      }
    }
    ```

```kotlin
val driver: SqlDriver = NativeSqliteDriver(Database.Schema, "test.db")
```

### Kotlin/Native Memory Models

The SQLDelight native driver supports the updated memory model only since 2.0.0.

{% include 'common/index_queries.md' %}

## Reader Connection Pools

Disk databases can (optionally) have multiple reader connections. To configure the reader pool, pass the `maxReaderConnections` parameter to the various constructors of `NativeSqliteDriver`:

```kotlin
val driver: SqlDriver = NativeSqliteDriver(
    Database.Schema, 
    "test.db", 
    maxReaderConnections = 4
)
```

Reader connections are only used to run queries outside of a transaction. Any write calls, and anything in a transaction, 
uses a single connection dedicated to transactions.
