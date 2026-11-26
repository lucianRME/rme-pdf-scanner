package org.synapseworks.m35.workerprobe;

import java.io.*;
import java.util.Locale;
import java.util.zip.*;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.ext.DefaultHandler2;

/** Streaming aggregate guard before the qualified per-part parser.
 * No document text is accumulated, logged or placed in an exception message.
 */
public final class OfficePreflight {
    private OfficePreflight() {}
    public interface Check { void run() throws Exception; }
    private static final class Limit extends IOException {
        final String code;
        Limit(String value) { super("Office preflight declined"); code = value; }
    }
    private static final class Budget {
        final Check check;
        final long until = System.nanoTime() + 12000000000L;
        long expanded, attributes; int entries, nodes, references; String failure;
        Budget(Check value) { check = value; }
        void guard() throws IOException {
            if (Thread.currentThread().isInterrupted()) fail("CANCELLED");
            if (System.nanoTime() > until) fail("PREFLIGHT_LIMIT");
            try { check.run(); } catch (Exception ignored) { fail("CANCELLED"); }
        }
        void fail(String code) throws Limit { if (failure == null) failure = code; throw new Limit(failure); }
    }
    private static final class Part extends FilterInputStream {
        final Budget budget; final boolean xml; long bytes;
        Part(InputStream source, Budget b, boolean x) { super(source); budget = b; xml = x; }
        void count(int value) throws IOException {
            budget.guard();
            if (value > 0) { bytes += value; budget.expanded += value; }
            if (budget.expanded > Admission.EXPANDED || (xml && bytes > Admission.XML)) budget.fail("PACKAGE_LIMIT");
        }
        @Override public int read() throws IOException { budget.guard(); int value = in.read(); count(value < 0 ? 0 : 1); return value; }
        @Override public int read(byte[] b, int offset, int length) throws IOException { budget.guard(); int value = in.read(b, offset, Math.min(length, 16384)); count(value); return value; }
        @Override public long skip(long n) throws IOException {
            byte[] bytes = new byte[4096]; long total = 0;
            while (total < n) { int count = read(bytes, 0, (int)Math.min(n - total, bytes.length)); if (count < 0) break; total += count; }
            return total;
        }
        @Override public void close() {} // Parser cannot close the enclosing ZIP.
    }
    public static void validate(File source, Check check) throws Exception {
        Budget budget = new Budget(check);
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(source))) {
            ZipEntry entry; byte[] buffer = new byte[16384];
            while ((entry = zip.getNextEntry()) != null) {
                budget.guard(); if (++budget.entries > 10000) budget.fail("PACKAGE_LIMIT");
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.length() > 512) budget.fail("PACKAGE_PATH");
                boolean xml = name.endsWith(".xml") || name.endsWith(".rels");
                Part part = new Part(zip, budget, xml);
                if (!xml) { while (part.read(buffer) != -1) { } continue; }
                SAXParserFactory factory = SAXParserFactory.newInstance(); factory.setNamespaceAware(true);
                XMLReader reader = factory.newSAXParser().getXMLReader();
                reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
                reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                DefaultHandler2 handler = new DefaultHandler2() {
                    int depth, nodes;
                    void guard() throws SAXException { try { budget.guard(); } catch (IOException failure) { throw new SAXException(failure); } }
                    void fail(String code) throws SAXException { try { budget.fail(code); } catch (Limit failure) { throw new SAXException(failure); } }
                    @Override public void startElement(String uri, String local, String q, Attributes attrs) throws SAXException {
                        guard();
                        if (++depth > 128 || ++nodes > 100000 || ++budget.nodes > 200000 || attrs.getLength() > 64) fail("XML_LIMIT");
                        for (int index = 0; index < attrs.getLength(); index++) {
                            int length = attrs.getValue(index).length(); budget.attributes += length;
                            if (length > 8192 || budget.attributes > 4L * 1024 * 1024) fail("XML_LIMIT");
                        }
                        String name = local.toLowerCase(Locale.ROOT);
                        if ((name.equals("relationship") || name.equals("override")) && ++budget.references > 10000) fail("PACKAGE_LIMIT");
                    }
                    @Override public void endElement(String uri, String local, String q) { depth--; }
                    @Override public void characters(char[] ch, int start, int length) throws SAXException { guard(); }
                    @Override public void startDTD(String n, String pub, String sys) throws SAXException { fail("XML_POLICY"); }
                    @Override public InputSource resolveEntity(String pub, String sys) throws SAXException { fail("XML_POLICY"); return null; }
                    @Override public void error(SAXParseException e) throws SAXException { fail("XML_POLICY"); }
                    @Override public void fatalError(SAXParseException e) throws SAXException { fail("XML_POLICY"); }
                };
                reader.setContentHandler(handler); reader.setErrorHandler(handler); reader.setEntityResolver(handler);
                reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
                reader.parse(new InputSource(part));
            }
        } catch (Exception failure) {
            if (budget.failure != null) throw new Admission.Reject(budget.failure);
            Throwable cause = failure;
            for (int index = 0; cause != null && index < 12; index++, cause = cause.getCause()) {
                if (cause instanceof Limit) throw new Admission.Reject(((Limit)cause).code);
            }
            if (failure instanceof ZipException) {
                String message = failure.getMessage();
                throw new Admission.Reject(message != null && message.startsWith("Invalid zip entry path: ") ? "PACKAGE_PATH" : "CORRUPT_INPUT");
            }
            throw new Admission.Reject("XML_POLICY");
        }
    }
}
