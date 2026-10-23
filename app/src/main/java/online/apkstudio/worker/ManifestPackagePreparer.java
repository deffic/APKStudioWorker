package online.apkstudio.worker;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/** Make a temporary manifest usable by stand-alone AAPT2; never edit sources. */
final class ManifestPackagePreparer {
    private static final long MAX_MANIFEST_BYTES = 1_000_000;
    private static final Pattern PACKAGE_NAME = Pattern.compile(
        "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");

    private ManifestPackagePreparer() { }

    static File prepare(File source, File destination, String applicationId) throws IOException {
        if (!source.isFile() || source.length() > MAX_MANIFEST_BYTES) {
            throw new IOException("Манифест отсутствует или слишком большой.");
        }
        if (destination.exists()) throw new IOException("Временный манифест уже существует.");
        try {
            // Android does NOT support DocumentBuilderFactory's
            // XMLConstants.FEATURE_SECURE_PROCESSING, unlike the host JDK.
            // Reject DTD declarations before handing any bytes to the parser.
            byte[] content = Files.readAllBytes(source.toPath());
            if (content.length > MAX_MANIFEST_BYTES) {
                throw new IOException("Манифест слишком большой.");
            }
            for (byte value : content) {
                if (value == 0) {
                    throw new IOException("Для манифеста поддерживается только UTF-8 XML.");
                }
            }
            String normalized = new String(content, StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
            if (normalized.contains("<!DOCTYPE") || normalized.contains("<!ENTITY")) {
                throw new IOException("DOCTYPE и ENTITY в манифесте запрещены.");
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (ParserConfigurationException unsupportedOnDevice) {
                // The bounded input scan and rejecting entity resolver remain active.
            }
            DocumentBuilder parser = factory.newDocumentBuilder();
            parser.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External XML entities are disabled.");
            });
            Document doc = parser.parse(new ByteArrayInputStream(content));
            Element manifest = doc.getDocumentElement();
            if (manifest == null || !"manifest".equals(manifest.getTagName())) {
                throw new IOException("Корневой XML-элемент не manifest.");
            }
            String packageName = isPackage(applicationId)
                ? applicationId : manifest.getAttribute("package");
            if (!isPackage(packageName)) {
                throw new IOException("Для AAPT2 нужен корректный applicationId в app/build.gradle(.kts) " +
                    "или package в AndroidManifest.xml.");
            }
            // AGP derives the final manifest package from applicationId. AAPT2
            // requires this attribute even when source manifests omit it.
            manifest.setAttribute("package", packageName);

            TransformerFactory transformers = TransformerFactory.newInstance();
            transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = transformers.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            try (OutputStream output = Files.newOutputStream(destination.toPath())) {
                transformer.transform(new DOMSource(doc), new StreamResult(output));
            }
            return destination;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Не удалось подготовить безопасную копию AndroidManifest.xml: " +
                error.getMessage(), error);
        }
    }

    static boolean isPackage(String value) {
        return value != null && PACKAGE_NAME.matcher(value).matches();
    }
}
