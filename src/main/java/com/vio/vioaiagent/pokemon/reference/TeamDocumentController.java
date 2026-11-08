package com.vio.vioaiagent.pokemon.reference;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.io.*;
import java.util.*;
import java.util.zip.ZipInputStream;

@RestController @RequestMapping("/pokemon/reference/team-document")
@Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.pokemon.reference.enabled",havingValue="true")
public class TeamDocumentController {
    @PostMapping(consumes="multipart/form-data")
    @io.swagger.v3.oas.annotations.Operation(operationId="extractTeamDocument")
    public Map<String,String> extract(@RequestParam("file") MultipartFile file) {
        if(file.isEmpty()||file.getSize()>1024*1024)throw new IllegalArgumentException("文档必须非空且不超过1 MB");
        String name=Objects.toString(file.getOriginalFilename(),"").toLowerCase(Locale.ROOT);
        try {
            byte[] bytes=file.getBytes();String text;
            if(name.endsWith(".pdf")) {
                if(bytes.length<5||!new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))throw new IllegalArgumentException("文件内容不是PDF");
                var out=new StringBuilder();
                try(var pdf=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(new ByteArrayInputStream(bytes)))) {
                    if(pdf.getNumberOfPages()>50)throw new IllegalArgumentException("PDF最多50页");
                    for(int i=1;i<=pdf.getNumberOfPages();i++){
                        out.append(com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(pdf.getPage(i))).append('\n');
                        if(out.length()>20000)throw new IllegalArgumentException("提取文本超过20000字，请拆分文件");
                    }
                }
                text=out.toString();
            } else if(name.endsWith(".docx")) text=docx(bytes);
            else throw new IllegalArgumentException("文档提取仅支持DOCX和文本PDF；不支持扫描件或宏文档");
            if(text.isBlank()||text.length()>20000)throw new IllegalArgumentException("没有可用文本或超过20000字；扫描件请先OCR并人工核对");
            return Map.of("text",text);
        }catch(IllegalArgumentException error){throw error;}
        catch(Exception error){throw new IllegalArgumentException("文档无法解析，请确认文件未损坏、未加密，并包含可提取的队伍文本");}
    }
    static String docx(byte[] bytes)throws Exception {
        byte[] document=null;int total=0,count=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                if(++count>500||entry.getName().toLowerCase(Locale.ROOT).contains("vbaproject"))throw new IllegalArgumentException("文档过复杂或包含宏");
                byte[] data=zip.readNBytes(4_000_001-total);total+=data.length;
                if(total>4_000_000)throw new IllegalArgumentException("DOCX解压内容过大");
                if(entry.getName().equals("word/document.xml")){if(document!=null)throw new IllegalArgumentException("文档主体重复");document=data;}
            }
        }
        if(document==null)throw new IllegalArgumentException("不是有效DOCX文档");
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var xml=factory.newDocumentBuilder().parse(new ByteArrayInputStream(document));
        var paragraphs=xml.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","p");
        var out=new StringBuilder();for(int i=0;i<paragraphs.getLength();i++){append(paragraphs.item(i),out);out.append('\n');if(out.length()>20000)throw new IllegalArgumentException("文档文本过长");}
        return out.toString();
    }
    private static void append(org.w3c.dom.Node node,StringBuilder out){
        append(node,out,0);
    }
    private static void append(org.w3c.dom.Node node,StringBuilder out,int depth){
        if(depth>64)throw new IllegalArgumentException("文档XML嵌套过深");
        if("t".equals(node.getLocalName())){out.append(node.getTextContent());return;}
        if("br".equals(node.getLocalName())){out.append('\n');return;}
        for(var child=node.getFirstChild();child!=null;child=child.getNextSibling())append(child,out,depth+1);
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException error){return Map.of("message",error.getMessage());}
}
