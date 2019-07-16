package no.nils.wsdl2java

import java.nio.charset.StandardCharsets

class Wsdl2JavaPluginExtension {
    String cxfVersion = "+"
    boolean deleteGeneratedSourcesOnClean = false
    String encoding = StandardCharsets.UTF_8.name()
    boolean stabilize = false
    boolean stabilizeAndMergeObjectFactory = false
    List<List<Object>> wsdlsToGenerate
    File generatedWsdlDir = new File(Wsdl2JavaPlugin.DEFAULT_DESTINATION_DIR)
}
