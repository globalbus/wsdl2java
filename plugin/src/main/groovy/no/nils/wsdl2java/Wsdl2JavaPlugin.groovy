package no.nils.wsdl2java

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.TaskProvider

class Wsdl2JavaPlugin implements Plugin<Project> {
    public static final String WSDL2JAVA = "wsdl2java"
    public static final String CLEAN = "deleteGeneratedSources"
    public static final String WSDL2JAVA_EXT = "wsdl2javaExt"

    public static final DEFAULT_DESTINATION_DIR = "build/generated-source"

    void apply(Project project) {
        // make sure the project has the java plugin
        project.plugins.apply(JavaPlugin.class)

        Wsdl2JavaPluginExtension ext = project.extensions.create(WSDL2JAVA_EXT, Wsdl2JavaPluginExtension.class)

        Configuration wsdl2javaConfiguration = project.configurations.maybeCreate(WSDL2JAVA)

        // add wsdl2java task with group and a description
        TaskProvider wsdl2java = project.tasks.register(WSDL2JAVA, Wsdl2JavaTask.class) { Wsdl2JavaTask task ->
            task.group = 'Wsdl2Java'
            task.description = 'Generate java source code from WSDL files.'
            task.classpath = wsdl2javaConfiguration
            def cxfVersion = ext.cxfVersion

            // add cxf as dependency
            project.dependencies {
                wsdl2java "org.apache.cxf:cxf-tools-wsdlto-databinding-jaxb:$cxfVersion"
                wsdl2java "org.apache.cxf:cxf-tools-wsdlto-frontend-jaxws:$cxfVersion"
            }
            task.dependsOn(wsdl2javaConfiguration)
            task.inputs.properties([stabilize          : ext.stabilize,
                                    'stabilizeAndMerge': ext.stabilizeAndMergeObjectFactory,
                                    'encoding'         : ext.encoding])
            task.outputs.dir(ext.generatedWsdlDir)
            project.sourceSets.main.java.srcDirs += ext.generatedWsdlDir
            task.setupInputs(ext.wsdlsToGenerate)
        }
        project.tasks.named(JavaPlugin.COMPILE_JAVA_TASK_NAME) { dependsOn wsdl2java }

        // add cleanXsd task with group and a description
        TaskProvider wsdlClean = project.tasks.register(CLEAN, CleanTask.class) { Task task ->
            group 'Wsdl2Java'
            description 'Delete java source code generated from WSDL and XSD files.'
        }
        if (ext.deleteGeneratedSourcesOnClean) {
            project.tasks.named(BasePlugin.CLEAN_TASK_NAME) { dependsOn wsdlClean }
        }
    }
}
