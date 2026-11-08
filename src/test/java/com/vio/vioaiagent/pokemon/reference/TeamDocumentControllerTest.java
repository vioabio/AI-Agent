package com.vio.vioaiagent.pokemon.reference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
class TeamDocumentControllerTest {
 byte[] zip(String xml)throws Exception{var out=new ByteArrayOutputStream();try(var z=new ZipOutputStream(out)){z.putNextEntry(new ZipEntry("word/document.xml"));z.write(xml.getBytes(StandardCharsets.UTF_8));z.closeEntry();}return out.toByteArray();}
 @Test void docxExtractsTextWithoutLoadingExternalReferences()throws Exception{
  var controller=new TeamDocumentController();
  var bytes=zip("<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>Squirtle</w:t></w:r></w:p><w:p><w:r><w:t>Ability: Torrent</w:t></w:r></w:p></w:body></w:document>");
  assertTrue(controller.extract(new MockMultipartFile("file","team.docx","application/octet-stream",bytes)).get("text").contains("Squirtle\nAbility: Torrent"));
  assertThrows(IllegalArgumentException.class,()->controller.extract(new MockMultipartFile("file","bad.pdf","application/pdf",bytes)));
  assertThrows(IllegalArgumentException.class,()->controller.extract(new MockMultipartFile("file","big.pdf","application/pdf",new byte[1_048_577])));
  var malicious=zip("<!DOCTYPE x [<!ENTITY ext SYSTEM 'file:///untrusted'>]><x>&ext;</x>");
  assertThrows(IllegalArgumentException.class,()->controller.extract(new MockMultipartFile("file","bad.docx","application/zip",malicious)));
 }
 @Test void textPdfExtractionUsesActualPdfText()throws Exception{
  var out=new ByteArrayOutputStream();try(var pdf=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfWriter(out));var document=new com.itextpdf.layout.Document(pdf)){document.add(new com.itextpdf.layout.element.Paragraph("Squirtle\nAbility: Torrent"));}
  assertTrue(new TeamDocumentController().extract(new MockMultipartFile("file","team.pdf","application/pdf",out.toByteArray())).get("text").contains("Ability: Torrent"));
 }
}
