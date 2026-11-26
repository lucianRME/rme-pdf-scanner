package org.synapseworks.m35.workerprobe;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.ext.DefaultHandler2;
import org.xml.sax.SAXParseException;

/** Bounded UI-only declaration review; never logs source values. Not an actual font mapping report. */
public final class OfficeAdmission {
    private OfficeAdmission() {}
    public static String failureCode(Exception failure) {
        return failure instanceof Admission.Reject ? ((Admission.Reject)failure).code : "SOURCE_REVIEW_FAILED";
    }
    public static String[] inspect(File source, String kind) throws Exception {
        OfficePreflight.validate(source, () -> { });
        Admission.validate(source, kind);
        Set<String> fonts = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(source)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                // Admission already bounded/validated all parts; inspect only font/style/theme declarations.
                if (!(name.endsWith(".xml") && (name.contains("/theme/") || name.endsWith("styles.xml") || name.endsWith("document.xml") || name.contains("/slides/")))) continue;
                SAXParserFactory factory = SAXParserFactory.newInstance(); factory.setNamespaceAware(true);
                XMLReader reader = factory.newSAXParser().getXMLReader();
                reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
                reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                reader.setEntityResolver((pub, sys) -> { throw new SAXException("XML_POLICY"); });
                DefaultHandler2 handler = new DefaultHandler2() {
                    int depth, nodes;
                    @Override public void startElement(String uri, String local, String q, Attributes attrs) throws SAXException {
                        if (Thread.currentThread().isInterrupted()) throw new SAXException("CANCELLED");
                        if (++depth > 128 || ++nodes > 100000 || attrs.getLength() > 64) throw new SAXException("XML_LIMIT");
                        for (int i = 0; i < attrs.getLength(); i++) {
                            String key = attrs.getLocalName(i), value = attrs.getValue(i);
                            if ((uri.equals("http://schemas.openxmlformats.org/wordprocessingml/2006/main") && local.equals("rFonts") && (key.equals("ascii") || key.equals("hAnsi"))) ||
                                (uri.equals("http://schemas.openxmlformats.org/drawingml/2006/main") && key.equals("typeface")) ||
                                (uri.equals("http://schemas.openxmlformats.org/spreadsheetml/2006/main") && local.equals("name") && key.equals("val"))) {
                                // Empty and script-specific theme placeholders are not invented substitutions.
                                if (!value.isEmpty()) {
                                    if (value.length() > 128 || value.codePoints().anyMatch(Character::isISOControl) || (!fonts.contains(value) && fonts.size() >= 64)) throw new SAXException("FONT_REVIEW_LIMIT");
                                    fonts.add(value);
                                }
                            }
                        }
                    }
                    @Override public void endElement(String uri, String local, String q) { depth--; }
                    @Override public void startDTD(String name, String pub, String sys) throws SAXException { throw new SAXException("XML_POLICY"); }
                    @Override public void error(SAXParseException e) throws SAXException { throw new SAXException("XML_POLICY"); }
                    @Override public void fatalError(SAXParseException e) throws SAXException { throw new SAXException("XML_POLICY"); }
                };
                reader.setContentHandler(handler); reader.setErrorHandler(handler);
                reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
                try (InputStream in = zip.getInputStream(entry)) { reader.parse(new InputSource(in)); }
            }
        }
        return new ArrayList<>(fonts).toArray(new String[0]);
    }
}
