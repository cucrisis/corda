@file:JvmName("DBMigration")

package com.r3.corda.dbmigration

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import net.corda.cliutils.CordaCliWrapper
import net.corda.cliutils.ExitCodes
import net.corda.cliutils.start
import net.corda.core.internal.copyTo
import net.corda.core.internal.div
import net.corda.core.internal.exists
import net.corda.core.schemas.MappedSchema
import net.corda.node.internal.DataSourceFactory.createDatasourceFromDriverJarFolders
import net.corda.node.internal.cordapp.JarScanningCordappLoader
import net.corda.node.services.config.ConfigHelper
import net.corda.node.services.config.NodeConfigurationImpl
import net.corda.node.services.config.configOf
import net.corda.node.services.persistence.DBCheckpointStorage
import net.corda.node.services.persistence.MigrationExporter
import net.corda.node.services.schema.NodeSchemaService
import net.corda.nodeapi.internal.MigrationHelpers
import net.corda.nodeapi.internal.config.UnknownConfigKeysPolicy
import net.corda.nodeapi.internal.config.parseAs
import net.corda.nodeapi.internal.persistence.CheckpointsException
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.nodeapi.internal.persistence.SchemaMigration
import org.slf4j.LoggerFactory
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import java.io.*
import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import javax.sql.DataSource

//command line arguments
const val DOORMAN_JAR_PATH = "doorman-jar-path"
const val EXECUTE_MIGRATION = "execute-migration"
const val DRY_RUN = "dry-run"
const val CREATE_MIGRATION_CORDAPP = "create-migration-sql-for-cordapp"
const val JAR_OUTPUT = "jar"
const val RELEASE_LOCK = "release-lock"

// output type
const val CONSOLE = "CONSOLE"

// initialise loggers lazily as some configuration is changed on startup and if loggers are already initialised it will be ignored
private val migrationLogger by lazy { LoggerFactory.getLogger("migration.tool") }
private val errorLogger by lazy { LoggerFactory.getLogger("errors") }

private enum class Mode {
    NODE, DOORMAN
}

private class DbManagementToolOptions {
    @Option(
            names = ["--mode"],
            description = ["The operating mode. \${COMPLETION-CANDIDATES}"]
    )
    var mode: Mode = Mode.NODE

    @Option(
            names = ["-b", "--base-directory"],
            description = ["The node or doorman directory."]
    )
    var baseDirectory: Path? = null

    @Option(
            names = ["-f", "--config-file"],
            description = ["The name of the config file. By default 'node.conf' for a simple node and 'network-management.conf' for a doorman."]
    )
    var configFile: String? = null

    @Option(
            names = ["--$DOORMAN_JAR_PATH"],
            description = ["The path to the doorman JAR."]
    )
    var doormanJarPath: Path? = null

    @Option(
            names = ["--$EXECUTE_MIGRATION"],
            description = ["This option will run the database migration on the configured database. This is the only command that will actually write to the database."]
    )
    var executeMigration: Boolean = false

    @Option(
            names = ["--$DRY_RUN"],
            arity = "0..1",
            description = ["Output the database migration to the specified output file.",
                "The output directory is the base-directory.",
                "You can specify a file name or 'CONSOLE' if you want to send the output to the console."]
    )
    var dryRun: String? = null

    @Option(
            names = ["--$CREATE_MIGRATION_CORDAPP"],
            arity = "0..1",
            description = ["Create migration files for a CorDapp.",
                "You can specify the fully qualified name of the `MappedSchema` class. If not specified it will generate the migration for all schemas that don't have migrations.",
                "The output directory is the base-directory, where a `migration` folder is created."]
    )
    var createMigrationSqlForCordappPath: String? = null

    @Option(
            names = ["--$JAR_OUTPUT"],
            arity = "0..0",
            description = ["Place generated migration scripts into a jar"]
    )
    var jarOutput: Boolean = false

    val createMigrationSqlForCordapp: Boolean get() = createMigrationSqlForCordappPath != null

    @Option(
            names = ["--$RELEASE_LOCK"],
            description = ["Releases whatever locks are on the database change log table, in case shutdown failed."]
    )
    var releaseLock: Boolean = false
}

fun main(args: Array<String>) {
    DbManagementTool().start(args)
}

data class Configuration(val dataSourceProperties: Properties, val database: DatabaseConfig, val jarDirs: List<String> = emptyList())

private class DbManagementTool : CordaCliWrapper("database-manager", "The Corda database management tool.") {
    @Mixin
    var cmdLineOptions = DbManagementToolOptions()

    private fun checkOnlyOneCommandSelected() {
        val selectedOptions = mutableListOf<String>()
        if (cmdLineOptions.dryRun != null) selectedOptions.add(DRY_RUN)
        if (cmdLineOptions.executeMigration) selectedOptions.add(EXECUTE_MIGRATION)
        if (cmdLineOptions.createMigrationSqlForCordapp) selectedOptions.add(CREATE_MIGRATION_CORDAPP)
        if (cmdLineOptions.releaseLock) selectedOptions.add(RELEASE_LOCK)

        require(selectedOptions.count() != 0) { "You must call database-manager with a command option. See --help for further info." }
        require(selectedOptions.count() == 1) { "You cannot call more than one of: ${selectedOptions.joinToString(", ")}. See --help for further info." }

        if (cmdLineOptions.jarOutput){
            require(cmdLineOptions.createMigrationSqlForCordapp) { "You must specify --$CREATE_MIGRATION_CORDAPP to use the --$JAR_OUTPUT option" }
        }
    }

    override fun runProgram(): Int {
        checkOnlyOneCommandSelected()
        require(cmdLineOptions.baseDirectory != null) { "You must specify a base directory" }
        fun baseDirectory() = cmdLineOptions.baseDirectory?.toAbsolutePath()?.normalize()!!
        fun configFile(defaultCfgName: String) = baseDirectory() / (cmdLineOptions.configFile ?: defaultCfgName)
        when {
            cmdLineOptions.mode == Mode.NODE -> {
                val baseDirectory = baseDirectory()
                if (!baseDirectory.exists()) {
                    error("Could not find base-directory: '$baseDirectory'.")
                }
                val config = configFile("node.conf")
                if (!config.exists()) {
                    error("Not a valid node folder. Could not find the config file: '$config'.")
                }
                val cordappsFolder = baseDirectory / "cordapps"
                val cordappLoader = JarScanningCordappLoader.fromDirectories(setOf(baseDirectory, cordappsFolder))

                val schemaService = NodeSchemaService(extraSchemas = cordappLoader.cordappSchemas)

                handleCommand(baseDirectory, config, cmdLineOptions.mode, cordappLoader.appClassLoader, schemaService.schemaOptions.keys, cmdLineOptions.jarOutput)
            }
            cmdLineOptions.mode == Mode.DOORMAN -> {
                if (cmdLineOptions.doormanJarPath != null) {
                    error("The $DOORMAN_JAR_PATH argument is required when running in doorman mode.")
                }
                val fatJarPath = cmdLineOptions.doormanJarPath!!
                if (!fatJarPath.exists()) {
                    error("Could not find the doorman jar in location: '$fatJarPath'.")
                }
                val doormanClassloader = classLoaderFromJar(fatJarPath)
                val doormanSchema = "com.r3.corda.networkmanage.common.persistence.NetworkManagementSchemaServices\$SchemaV1"
                val schema = loadMappedSchema(doormanSchema, doormanClassloader)
                handleCommand(baseDirectory(), configFile("network-management.conf"), cmdLineOptions.mode, doormanClassloader, setOf(schema), false)
            }
        }
        migrationLogger.info("Done")
        return ExitCodes.SUCCESS
    }

    private fun handleCommand(baseDirectory: Path, configFile: Path, mode: Mode, classLoader: ClassLoader, schemas: Set<MappedSchema>, jarOutput: Boolean) {
        val parsedConfig = ConfigFactory.parseFile(configFile.toFile()).resolve().let {
            if (mode == Mode.NODE) {
                it.withFallback(configOf("baseDirectory" to baseDirectory.toString()))
                        .withFallback(ConfigFactory.parseResources("reference.conf", ConfigParseOptions.defaults().setAllowMissing(true)))
                        .resolve()
            } else {
                it
            }
        }
        val config = parsedConfig.parseAs(Configuration::class, UnknownConfigKeysPolicy.IGNORE::handle)

        fun runMigrationCommand(withMigration: (SchemaMigration, DataSource) -> Unit): Unit = runWithDataSource(config, baseDirectory, classLoader) { dataSource ->
            withMigration(SchemaMigration(schemas, dataSource, config.database, classLoader), dataSource)
        }

        when {
            cmdLineOptions.releaseLock -> runWithDataSource(ConfigFactory.parseFile(configFile.toFile()).resolve().parseAs(Configuration::class, UnknownConfigKeysPolicy.IGNORE::handle), baseDirectory, classLoader) {
                SchemaMigration(emptySet(), it, config.database, Thread.currentThread().contextClassLoader).forceReleaseMigrationLock()
            }
            cmdLineOptions.dryRun != null -> {
                val writer = getMigrationOutput(baseDirectory)
                migrationLogger.info("Exporting the current db migrations ...")
                runMigrationCommand { migration, _ ->
                    migration.generateMigrationScript(writer)
                }
            }
            cmdLineOptions.executeMigration -> {
                migrationLogger.info("Running the database migration on  $baseDirectory")
                runMigrationCommand { migration, dataSource -> migration.runMigration(dataSource.connection.use { DBCheckpointStorage().getCheckpointCount(it) != 0L }) }
            }
            cmdLineOptions.createMigrationSqlForCordapp && (mode == Mode.NODE) -> {

                // do we really want to creating captured functions here.
                fun generateMigrationFileForSchema(schemaClass: String): Pair<Class<*>, Path>? {
                    migrationLogger.info("Creating database migration files for schema: $schemaClass into ${(baseDirectory / "migration").toString().trim()}")
                    var liquiBaseOutput: Pair<Class<*>, Path>? = null
                    try {
                        runWithDataSource(config, baseDirectory, classLoader) {
                            liquiBaseOutput = MigrationExporter(baseDirectory, config.dataSourceProperties, classLoader, it).generateMigrationForCorDapp(schemaClass)
                        }
                    } catch (e: Exception) {
                        wrappedError("Could not generate migration for $schemaClass: ${e.message}", e)
                    }
                    return liquiBaseOutput
                }

                val outputSchemaMigrations = mutableListOf<Pair<Class<*>, Path>>()

                if (cmdLineOptions.createMigrationSqlForCordappPath != "") {
                    generateMigrationFileForSchema(cmdLineOptions.createMigrationSqlForCordappPath!!)?.let { outputSchemaMigrations.add(it) }
                } else {
                    outputSchemaMigrations.addAll(schemas.filter { true || MigrationHelpers.getMigrationResource(it, classLoader) == null }.mapNotNull {
                        generateMigrationFileForSchema(it.javaClass.name)
                    })
                }

                if (jarOutput) {
                    val groupedBySourceJar = outputSchemaMigrations.map { (klazz, path) ->
                        //get the source jar for this particular schema
                        klazz.protectionDomain.codeSource to path
                    }.filter {
                        //filter all entries without a code source
                        it.first != null
                    }.map {
                        //convert codesource to a File
                        File(it.first.location.toURI()) to it.second
                    }.groupBy {
                        //group by codesource File
                        it.first
                    }.map {
                        // convert File into a filename for the codesource
                        // if run from within an IDE, there is possibility of some schemas to be
                        // loaded from a build folder
                        // so we must handle this case
                        val fileName = if (it.key.name.endsWith("jar")) {
                            it.key.path
                        } else {
                            "unknown-cordapp.jar"
                        }
                        fileName to it.value.map { it.second }
                    }.toMap()

                    groupedBySourceJar.entries.forEach { (_, migrationFiles) ->
                        migrationFiles.forEach {
                            //use System.out directly instead of logger as we MUST output to screen due to user input requirement
                            System.out.println("#####${it.fileName}#####")
                            it.copyTo(System.out)
                            System.out.println("Is this output as expected? [y/N]")
                            validateUserResponse()
                        }
                    }

                    System.out.println("There is potential for data corruption.")
                    System.out.println("Are you sure that the migration scripts are acceptable? [y/N]")
                    validateUserResponse()

                    groupedBySourceJar.entries.forEach { (jar, migrationFiles) ->
                        val sourceJarFile = File(jar)
                        val migrationJarFile = File(sourceJarFile.parent, "migration-${sourceJarFile.name}")
                        JarOutputStream(FileOutputStream(migrationJarFile)).use { jos ->
                            migrationLogger.info("Creating migration cordapp at: ${migrationJarFile.absolutePath}")
                            migrationFiles.map { ZipEntry("migration/${it.fileName}") }.forEach {
                                jos.putNextEntry(it)
                            }
                        }
                    }
                }

            }
            else -> error("Please specify a correct command")
        }
    }

    private fun validateUserResponse() {
        val userInput = Scanner(System.`in`).nextLine().toLowerCase()
        if (userInput != "y" && userInput != "yes") {
            migrationLogger.warn("quitting due to user input")
            System.exit(22)
        }
    }

    private fun classLoaderFromJar(jarPath: Path): ClassLoader = URLClassLoader(listOf(jarPath.toUri().toURL()).toTypedArray())

    private fun loadMappedSchema(schemaName: String, classLoader: ClassLoader) = classLoader.loadClass(schemaName).kotlin.objectInstance as MappedSchema

    private fun getMigrationOutput(baseDirectory: Path): Writer {
        return when (cmdLineOptions.dryRun) {
            "" -> FileWriter(File(baseDirectory.toFile(), "migration${SimpleDateFormat("yyyyMMddHHmmss").format(Date())}.sql"))
            CONSOLE -> PrintWriter(System.out)
            else -> FileWriter(File(baseDirectory.toFile(), cmdLineOptions.dryRun))
        }
    }

    private fun runWithDataSource(config: Configuration, baseDirectory: Path, classLoader: ClassLoader, withDatasource: (DataSource) -> Unit) {
        val driversFolder = (baseDirectory / "drivers").let { if (it.exists()) listOf(it) else emptyList() }
        val jarDirs = config.jarDirs.map { Paths.get(it) }
        for (jarDir in jarDirs) {
            if (!jarDir.exists()) {
                error("Could not find the configured JDBC driver directory: '$jarDir'.")
            }
        }

        return try {
            withDatasource(createDatasourceFromDriverJarFolders(config.dataSourceProperties, classLoader, driversFolder + jarDirs))
        } catch (e: CheckpointsException) {
            error(e)
        } catch (e: ClassNotFoundException) {
            wrappedError("Class not found in CorDapp", e)
        } catch (e: Exception) {
            wrappedError("""Failed to create datasource.
                |Please check that the correct JDBC driver is installed in one of the following folders:
                |${(driversFolder + jarDirs).joinToString("\n\t - ", "\t - ")}
                |Caused By $e""".trimMargin(), e)
        }
    }

    private fun wrappedError(message: String, innerException: Exception) {
        errorLogger.error(message, innerException)
        throw WrappedConfigurationException(message, innerException)
    }

    private fun error(exception: Exception) {
        errorLogger.error(exception.message, exception)
        throw exception
    }

    private fun error(message: String) {
        errorLogger.error(message)
        throw ConfigurationException(message)
    }
}

class ConfigurationException(message: String) : Exception(message)
class WrappedConfigurationException(message: String, val innerException: Exception) : Exception(message)