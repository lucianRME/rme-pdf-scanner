package org.synapseworks.m35.workerprobe;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.ext.LexicalHandler;
/** Conservative prototype admission; rejection is never silent sanitization. */
final class Admission {
  static final long INPUT=20L*1024*1024, EXPANDED=200L*1024*1024;
  static final int XML=6*1024*1024, IMAGE=16*1024*1024;
  static final class Reject extends Exception {final String code;Reject(String code){super(code);this.code=code;}}
  static final class Ref {final String part,target,type;Ref(String p,String t){this(p,t,null);}Ref(String p,String t,String k){part=p;target=t;type=k;}}
  static final class TextBudget {int codepoints;final Map<String,String>types=new HashMap<>();final Set<String>charts=new HashSet<>();}
  static void validate(File file,String kind)throws Reject {
    if(!Arrays.asList("docx","xlsx","pptx").contains(kind))throw new Reject("SOURCE_KIND");
    if(file.length()<=0||file.length()>INPUT)throw new Reject("INPUT_LIMIT");
    HashSet<String> names=new HashSet<>(), folded=new HashSet<>();ArrayList<Ref> refs=new ArrayList<>();long expanded=0;int entries=0;TextBudget text=new TextBudget();
    try(ZipInputStream zip=new ZipInputStream(new FileInputStream(file))) {
      ZipEntry e;byte[] buffer=new byte[16384];
      while((e=zip.getNextEntry())!=null) {
        if(Thread.currentThread().isInterrupted())throw new Reject("CANCELLED");
        String n=e.getName(),low=n.toLowerCase(Locale.ROOT);
        if(++entries>10000||n.length()>512||n.startsWith("/")||n.contains("\\")||n.indexOf('\0')>=0||Arrays.asList(n.split("/")).contains("..")||!n.matches("[\\x20-\\x7e]+")||!names.add(n)||!folded.add(low))throw new Reject("PACKAGE_PATH");
        if(low.contains("vbaproject")||low.contains("/activex/")||low.contains("/embeddings/")||low.startsWith("_xmlsignatures/")||low.contains("/customui/")||low.contains("macrosheets"))throw new Reject("ACTIVE_CONTENT");
        if(low.endsWith(".odttf")||low.contains("/fonts/"))throw new Reject("UNSUPPORTED_FONT");
        boolean xml=low.endsWith(".xml")||low.endsWith(".rels");boolean image=low.contains("/media/");
        ByteArrayOutputStream bytes=(xml||image)?new ByteArrayOutputStream():null;long count=0;int r;
        while((r=zip.read(buffer))!=-1) {
          if(Thread.currentThread().isInterrupted())throw new Reject("CANCELLED");
          expanded+=r;count+=r;
          if(expanded>EXPANDED||xml&&count>XML||image&&count>IMAGE)throw new Reject("PACKAGE_LIMIT");
          if(bytes!=null)bytes.write(buffer,0,r);
        }
        if(xml)parse(n,bytes.toByteArray(),refs,text);
        if(image)image(bytes.toByteArray());
      }
      String main=kind.equals("docx")?"word/document.xml":kind.equals("xlsx")?"xl/workbook.xml":"ppt/presentation.xml";
      if(!names.contains("[Content_Types].xml")||!names.contains(main))throw new Reject("SOURCE_KIND");
      for(Ref ref:refs) {
        String target=ref.target;if(target.contains("%"))throw new Reject("EXTERNAL_RESOURCE");
        URI uri=new URI(target);
        if(uri.getScheme()!=null||uri.getAuthority()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new Reject("EXTERNAL_RESOURCE");
        String part=ref.part;int marker=part.lastIndexOf("/_rels/");String base=marker>=0?part.substring(0,marker+1):"";
        String path=uri.getPath();if(path==null||path.isEmpty())throw new Reject("EXTERNAL_RESOURCE");
        String normalized=new URI(path.startsWith("/")?path.substring(1):base+path).normalize().getPath();
        if(normalized.startsWith("../")||!names.contains(normalized))throw new Reject("PACKAGE_REFERENCE");
        if(ref.type!=null&&ref.type.endsWith("/chart")&&(!text.charts.contains(normalized)||!"application/vnd.openxmlformats-officedocument.drawingml.chart+xml".equals(text.types.get("/"+normalized))))throw new Reject("CHART_POLICY");
      }
    }catch(Reject ex){throw ex;}catch(ZipException ex){
      // Android U+ may refuse traversal before returning a ZipEntry. Do not disable its guard.
      String message=ex.getMessage();
      throw new Reject(message!=null&&message.startsWith("Invalid zip entry path: ")?"PACKAGE_PATH":"CORRUPT_INPUT");
    }catch(Exception ex){throw new Reject("CORRUPT_INPUT");}
  }
  private static void parse(String part,byte[] bytes,ArrayList<Ref> refs,TextBudget budget)throws Reject {
    try {
      SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(true);
      XMLReader reader=factory.newSAXParser().getXMLReader();
      reader.setFeature("http://xml.org/sax/features/external-general-entities",false);
      reader.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
      Handler h=new Handler(part,refs,budget);reader.setContentHandler(h);reader.setErrorHandler(h);reader.setEntityResolver((pub,sys)->{throw new SAXException("XML_POLICY");});
      reader.setProperty("http://xml.org/sax/properties/lexical-handler",h);
      reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
    }catch(Exception ex){if(ex instanceof SAXException&&((SAXException)ex).getException()instanceof Reject)throw(Reject)((SAXException)ex).getException();throw new Reject("XML_POLICY");}
  }
  private static final class Handler extends DefaultHandler implements LexicalHandler {
    final String part;final ArrayList<Ref>refs;final TextBudget budget;int depth,nodes,textDepth=-1;StringBuilder formula,text;String formulaElement;
    final Set<String> active=new HashSet<>(Arrays.asList("oleobject","control","externaldata","externallink","externalreference","connection","querytable","script","eventlistener","hyperlink","hlinkclick","hlinkmouseover","hlinkhover","action","object","applet","docevents","altchunk","ddelink","ddeitem","ddeitems","mailmerge"));
    Handler(String p,ArrayList<Ref>r,TextBudget b){part=p;refs=r;budget=b;}
    void fail(String code)throws SAXException {throw new SAXException(new Reject(code));}
    void textPolicy(CharSequence value)throws SAXException {
      for(int i=0;i<value.length();){char first=value.charAt(i);if(Character.isSurrogate(first)&&(!Character.isHighSurrogate(first)||i+1>=value.length()||!Character.isLowSurrogate(value.charAt(i+1))))fail("XML_POLICY");int cp=Character.codePointAt(value,i);i+=Character.charCount(cp);
        if(++budget.codepoints>200000)fail("TEXT_LIMIT");if(!TextPolicyTables.allowed(cp))fail("UNSUPPORTED_SCRIPT");if(!TextPolicyTables.glyph(cp))fail("UNSUPPORTED_GLYPH");}
    }
    public void startElement(String uri,String local,String q,Attributes attrs)throws SAXException {
      if(Thread.currentThread().isInterrupted())fail("CANCELLED");
      if(++depth>128||++nodes>100000||attrs.getLength()>64)fail("XML_LIMIT");
      String name=local.toLowerCase(Locale.ROOT);
      if(uri.startsWith("http://purl.oclc.org/ooxml/"))fail("UNSUPPORTED_PROFILE");
      if(depth==1&&local.equals("chartSpace")&&uri.equals("http://schemas.openxmlformats.org/drawingml/2006/chart"))budget.charts.add(part);
      boolean content=(uri.equals("http://schemas.openxmlformats.org/wordprocessingml/2006/main")&&(name.equals("t")||name.equals("deltext")))||(uri.equals("http://schemas.openxmlformats.org/drawingml/2006/main")&&name.equals("t"))||(uri.equals("http://schemas.openxmlformats.org/spreadsheetml/2006/main")&&(name.equals("t")||name.equals("v")||name.endsWith("header")||name.endsWith("footer")))||(uri.equals("http://schemas.openxmlformats.org/drawingml/2006/chart")&&name.equals("v"));
      if(content&&textDepth<0){textDepth=depth;text=new StringBuilder();}
      if(active.contains(name))fail("ACTIVE_CONTENT");
      if(name.equals("fldsimple")||name.equals("instrtext")||name.equals("fldchar"))fail("UNSUPPORTED_FIELD");
      for(int i=0;i<attrs.getLength();i++) {
        String key=attrs.getLocalName(i).toLowerCase(Locale.ROOT),v=attrs.getValue(i),low=v.toLowerCase(Locale.ROOT);
        if(v.length()>8192)fail("XML_LIMIT");
        if(key.equals("action")&&!v.isEmpty())fail("ACTIVE_CONTENT");
        if((key.equals("classid")||key.equals("clsid")||key.equals("progid"))&&!v.isEmpty())fail("ACTIVE_CONTENT");
        if(key.equals("targetmode")&&!v.equals("Internal"))fail("EXTERNAL_RESOURCE");
        if(key.equals("contenttype")&&(low.contains("macro")||low.contains("vbaproject")||low.contains("activex")||low.contains("oleobject")||low.contains("text/html")||low.contains("xhtml")||low.contains("text/rtf")))fail("ACTIVE_CONTENT");
        if((key.equals("href")||key.equals("src"))&&!v.isEmpty())fail("EXTERNAL_RESOURCE");
        if(name.equals("override")&&key.equals("partname"))refs.add(new Ref(part,v));
        if(name.equals("definedname")&&key.equals("name")&&(low.contains("auto_open")||low.contains("auto_close")))fail("ACTIVE_CONTENT");
        if(name.equals("textpath")&&key.equals("string"))textPolicy(v);
      }
      if(name.equals("override")){String path=attrs.getValue("PartName"),type=attrs.getValue("ContentType");if(path!=null&&type!=null)budget.types.put(path,type);}
      if(name.equals("relationship")) {String target=attrs.getValue("Target"),type=attrs.getValue("Type");if(type!=null&&(type.endsWith("/hyperlink")||type.endsWith("/attachedTemplate")||type.endsWith("/oleObject")))fail("EXTERNAL_RESOURCE");if(target!=null)refs.add(new Ref(part,target,type));}
      if(name.equals("f")||name.equals("definedname")){formula=new StringBuilder();formulaElement=local;}
    }
    public void characters(char[]ch,int start,int length)throws SAXException {if(formula!=null){if(formula.length()+length>32768)fail("FORMULA_LIMIT");formula.append(ch,start,length);}if(text!=null){if(text.length()+length>400000)fail("TEXT_LIMIT");text.append(ch,start,length);}}
    public void endElement(String uri,String local,String q)throws SAXException {
      if(textDepth==depth){textPolicy(text);text=null;textDepth=-1;}
      if(formula!=null&&local.equals(formulaElement)) {
        String f=formula.toString().toUpperCase(Locale.ROOT);
        if(f.contains("[")||f.contains("]")||f.contains("|")||f.contains("://"))fail("UNSUPPORTED_FORMULA");
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("([A-Z_][A-Z_0-9.]*)\\s*\\(").matcher(f);
        Set<String>safe=new HashSet<>(Arrays.asList("SUM","AVERAGE","MIN","MAX","COUNT","COUNTA","ROUND","IF","AND","OR","ABS"));
        while(m.find())if(!safe.contains(m.group(1)))fail("UNSUPPORTED_FORMULA");
        formula=null;formulaElement=null;
      }
      depth--;
    }
    public void startDTD(String n,String pub,String sys)throws SAXException {fail("XML_POLICY");}
    public void endDTD(){}public void startEntity(String n){}public void endEntity(String n){}public void startCDATA(){}public void endCDATA(){}public void comment(char[]ch,int a,int b){}
    public void error(SAXParseException e)throws SAXException {fail("XML_POLICY");}public void fatalError(SAXParseException e)throws SAXException {fail("XML_POLICY");}
  }
  private static void image(byte[] b)throws Reject {
    long width=0,height=0;
    if(b.length>=24&&b[0]==(byte)137&&b[1]==80&&b[2]==78&&b[3]==71) {
      for(int i=16;i<20;i++)width=(width<<8)|(b[i]&255);for(int i=20;i<24;i++)height=(height<<8)|(b[i]&255);
    }else if(b.length>4&&b[0]==(byte)255&&b[1]==(byte)216) {
      for(int i=2;i+8<b.length;) {
        if((b[i++]&255)!=255)throw new Reject("IMAGE_PROFILE");int marker=b[i++]&255;
        while(marker==255&&i<b.length)marker=b[i++]&255;
        if(marker==217||marker==218)break;if(marker==216||marker>=208&&marker<=215)continue;
        int size=((b[i]&255)<<8)|(b[i+1]&255);if(size<2||i+size>b.length)throw new Reject("IMAGE_PROFILE");
        if(marker>=192&&marker<=207&&marker!=196&&marker!=200&&marker!=204){height=((b[i+3]&255)<<8)|(b[i+4]&255);width=((b[i+5]&255)<<8)|(b[i+6]&255);break;}i+=size;
      }
    }else throw new Reject("IMAGE_PROFILE");
    if(width<=0||height<=0||width>6000||height>6000||width*height>12500000)throw new Reject("IMAGE_LIMIT");
  }
}
