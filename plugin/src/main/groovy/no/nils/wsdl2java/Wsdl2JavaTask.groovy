package no.nils.wsdl2java

import groovy.io.FileType
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.*

import java.security.MessageDigest

@CacheableTask
class Wsdl2JavaTask extends DefaultTask {
    private static final NEWLINE = System.getProperty("line.separator")
    Wsdl2JavaPluginExtension ext

    @Classpath
    @InputFiles
    FileCollection wsdlDir = project.convention.getPlugin(JavaPluginConvention.class).sourceSets.main.resources

    /**
     * The Locale for the generated Java classes.
     */
    Locale locale = Locale.getDefault()

    // build internal properties
    Configuration classpath
    ClassLoader classLoader

    def setupInputs(List<List<Object>> wsdlsToGenerate) {
        def files = wsdlsToGenerate.flatten().findAll {
            v -> v instanceof File
        }
        def settings = wsdlsToGenerate.flatten().findAll {
            v -> !(v instanceof File)
        }.collect { v -> v.toString() }
        settings.findAll { v ->
            if (new File(v.toString()).exists())
                logger.error('{} \nLooks to be a file but it\'s not a File Object. This will break task caching!', v)
        }
        inputs.files(files).withPropertyName('files').withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property('generationSettings', settings)
    }

    @TaskAction
    def wsdl2java() {
        ext = project.extensions.getByType(Wsdl2JavaPluginExtension.class)
        deleteOutputFolders()
        MessageDigest md5 = MessageDigest.getInstance("MD5")

        File tmpDir = new File(project.buildDir, "wsdl2java")
        tmpDir.deleteDir()

        if (classpath == null) {
            classpath = project.configurations.getByName(Wsdl2JavaPlugin.WSDL2JAVA)
        }
        setupClassLoader()
        assert classLoader != null
        ext.wsdlsToGenerate.each { args ->
            String wsdlPath = md5.digest(args[-1].toString().bytes).encodeHex().toString()
            File targetDir = new File(tmpDir, wsdlPath)

            args.add(args.size - 1, '-d')
            args.add(args.size - 1, targetDir)
            String[] wsdl2JavaArgs = new String[args.size()];
            for (int i = 0; i < args.size(); i++)
                wsdl2JavaArgs[i] = args[i]

            def wsdlToJava = classLoader.loadClass("org.apache.cxf.tools.wsdlto.WSDLToJava")
                    .getDeclaredConstructor().newInstance()
            def toolContext = classLoader.loadClass("org.apache.cxf.tools.common.ToolContext")
                    .getDeclaredConstructor().newInstance()
            wsdlToJava.args = wsdl2JavaArgs
            // WSDLToJava w2j = new WSDLToJava(wsdl2JavaArgs);

            runWithLocale(this.locale) { ->
                try {
                    wsdlToJava.run(toolContext)
                } catch (Exception e) {
                    throw new TaskExecutionException(this, e)
                }
            }

            copyToOutputDir(targetDir)
        }
    }

    private void setupClassLoader() {
        if (classpath?.files) {
            def urls = classpath.files.collect { it.toURI().toURL() }

            classLoader = new URLClassLoader(urls as URL[], Thread.currentThread().contextClassLoader)
            Thread.currentThread().contextClassLoader = classLoader
        } else {
            classLoader = Thread.currentThread().contextClassLoader
        }
    }

    protected void runWithLocale(Locale locale, Closure<Void> closure) {
        // save the current default locale – will be set back at the end
        Locale currentDefaultLocale = Locale.getDefault()
        try {
            // set the wanted locale for the generated java classes
            Locale.setDefault(locale)

            closure()
        }
        finally {
            // set the default locale back to the previous default
            Locale.setDefault(currentDefaultLocale)
        }
    }

    protected void deleteOutputFolders() {
        ext.generatedWsdlDir.deleteDir()
    }

    private Set<String> findPackagePaths() {
        Set<String> packagePaths = new HashSet<>()
        for (List<String> args : ext.wsdlsToGenerate) {
            int packageArgIdx = args.indexOf("-p")
            int packageIx = packageArgIdx + 1
            if (packageArgIdx != -1 && args.size() >= packageIx) {
                //check if it's wsdl-namespace=package
                String[] maybeWsdlNameSpaceAndPackage = args.get(packageIx).split("=")
                String packageName = maybeWsdlNameSpaceAndPackage.size() == 1 ? maybeWsdlNameSpaceAndPackage[0] : maybeWsdlNameSpaceAndPackage[1]
                String pathPath = packageName.replace(".", "/")
                packagePaths.add(pathPath)
            }
        }
        return packagePaths;
    }

    protected void copyToOutputDir(File srcDir) {
        int srcPathLength = srcDir.getAbsolutePath().size() + 1

        srcDir.eachFileRecurse(FileType.FILES) { file ->
            String relPath = file.getAbsolutePath().substring(srcPathLength)
            File target = new File(ext.generatedWsdlDir, relPath)

            switchToEncoding(file)

            if (ext.stabilizeAndMergeObjectFactory) {
                mergeAndStabilizeObjectFactory(file, target)
            } else {
                project.ant.copy(file: file, tofile: target)
            }
        }
    }

    protected void switchToEncoding(File file) {
        List<String> lines = file.getText().split(NEWLINE)
        file.delete()

        if (ext.stabilize) {
            stripCommentDates(lines)
            stabilizeCommentLinks(file, lines)
            stabilizeXmlElementRef(file, lines)
            stabilizeXmlSeeAlso(file, lines)
        }

        String text = lines.join(NEWLINE) + NEWLINE  // want empty line last
        file.withWriter(ext.encoding) { w -> w.write(text) }
    }

    void stripCommentDates(List<String> lines) {
        String prevLine = ""
        for (ListIterator<String> lix = lines.listIterator(); lix.hasNext();) {
            String l = lix.next()
            if (prevLine.contains("This class was generated") && l.startsWith(" * 201")) {
                lix.remove()
            }
            prevLine = l
        }
    }

    void stabilizeCommentLinks(File file, List<String> lines) {
        for (ListIterator<String> lix = lines.listIterator(); lix.hasNext();) {
            String l = lix.next()

            if (l.contains("* {@link")) {
                int start = lix.previousIndex()

                while (lix.hasNext()) {
                    l = lix.next()
                    if (!l.contains("* {@link")) {
                        int end = lix.previousIndex()

                        List<String> subList = lines.subList(start, end);
                        Collections.sort(subList)

                        break
                    }
                }
            }
        }
    }

    void stabilizeXmlSeeAlso(File file, List<String> lines) {
        String seeAlsoStart = "@XmlSeeAlso({"
        String seeAlsoEnd = "})"
        for (ListIterator<String> lix = lines.listIterator(); lix.hasNext();) {
            String l = lix.next()

            if (l.startsWith(seeAlsoStart) && l.endsWith(seeAlsoEnd)) {
                List<String> classes = l.replace(seeAlsoStart, "").replace(seeAlsoEnd, "").split(",").collect {
                    it.trim()
                }
                String sortedClasses = seeAlsoStart + classes.sort().join(", ") + seeAlsoEnd
                lix.set(sortedClasses)
            }
        }
    }

    void stabilizeXmlElementRef(File file, List<String> lines) {
        String prevLine = ""
        for (ListIterator<String> lix = lines.listIterator(); lix.hasNext();) {
            String l = lix.next()

            if (l.contains("@XmlElementRef") && prevLine.contains("@XmlElementRefs")) {
                int start = lix.previousIndex()

                while (lix.hasNext()) {
                    l = lix.next()
                    if (!l.contains("@XmlElementRef")) {
                        int end = lix.previousIndex()

                        List<String> subList = lines.subList(start, end);
                        Collections.sort(subList)

                        // Fix ,-separation of lines
                        for (ListIterator<String> subLix = subList.listIterator(); subLix.hasNext();) {
                            String line = subLix.next()

                            line = line.replaceFirst(',$', "")
                            if (subLix.hasNext()) {
                                line = line + ","
                            }
                            subLix.set(line)
                        }
                        break
                    }
                }
            }
            prevLine = l
        }
    }

    protected void mergeAndStabilizeObjectFactory(File src, File target) {
        if (!target.exists()) {
            target.getParentFile().mkdirs()
            project.ant.copy(file: src, tofile: target)
            stabilizeObjFacWithItself(target);
        } else {
            stabilizeObjFacWithTarget(src, target)
        }
    }

    private void stabilizeObjFacWithItself(File target) {
        if (isObjectFactory(target)) {
            getLogger().info(" stabilize ${target}")
            ObjectFactoryMerger.merge(target, target, ext.encoding)
        }
    }

    private stabilizeObjFacWithTarget(File src, File target) {
        if (isObjectFactory(src) && src.getText(ext.encoding) != target.getText(ext.encoding)) {
            getLogger().info(" merge     ${target}")
            ObjectFactoryMerger.merge(src, target, ext.encoding)
        }
    }

    private boolean isObjectFactory(File f) {
        return "ObjectFactory.java" == f.getName()
    }
}