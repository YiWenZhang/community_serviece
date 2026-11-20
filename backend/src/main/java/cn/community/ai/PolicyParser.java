package cn.community.ai;

import cn.community.common.Problem;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

@Component
public class PolicyParser {
  public record Part(
      int ordinal, String section, Integer page, int start, int end, String content) {}

  public String extract(String filename, byte[] bytes) {
    String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    try {
      String text =
          switch (ext) {
            case "pdf" -> {
              try (var pdf = Loader.loadPDF(bytes)) {
                Problem.require(!pdf.isEncrypted(), "请先移除PDF密码后再上传");
                Problem.require(pdf.getNumberOfPages() <= 200, "PDF最多200页");
                var stripper = new PDFTextStripper();
                var out = new StringBuilder();
                for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                  stripper.setStartPage(page);
                  stripper.setEndPage(page);
                  if (page > 1) out.append('\f');
                  out.append(stripper.getText(pdf));
                  if (out.length() > 120000) throw new Problem(422, "文档正文最多12万字符，请拆分上传");
                }
                yield out.toString();
              }
            }
            case "docx" -> {
              try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes));
                  var extractor = new XWPFWordExtractor(doc)) {
                yield extractor.getText();
              }
            }
            case "txt", "md" ->
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            default -> throw new Problem(422, "支持PDF、DOCX、UTF-8 TXT及Markdown文档");
          };
      text =
          text.replace("\r\n", "\n")
              .replace('\r', '\n')
              .replace("\u0000", "")
              .replace("\uFEFF", "");
      Problem.require(text.strip().length() >= 30, "未提取到有效正文；扫描件请先OCR转为可搜索PDF或DOCX");
      Problem.require(text.length() <= 120000, "文档正文最多12万字符，请拆分上传");
      return text;
    } catch (Problem e) {
      throw e;
    } catch (CharacterCodingException e) {
      throw new Problem(422, "文本文件须使用UTF-8编码");
    } catch (Exception e) {
      throw new Problem(422, "文档解析失败，请检查文件是否损坏、加密或与扩展名不符");
    }
  }

  public List<Part> split(String text) {
    List<Part> result = new ArrayList<>();
    Pattern heading =
        Pattern.compile("(?m)^(?:#{1,6}\\s+[^\\n]+|第[一二三四五六七八九十百零〇0-9]+[章节条][^\\n]{0,120})");
    for (int start = 0; start < text.length(); ) {
      int end = Math.min(start + 1100, text.length());
      int pageBreak = text.indexOf('\f', start);
      if (pageBreak >= start && pageBreak < end) end = pageBreak + 1;
      if (end < text.length() && end == start + 1100) {
        int line = text.lastIndexOf('\n', end);
        if (line > start + 550) end = line + 1;
      }
      String content = text.substring(start, end);
      String section = "正文";
      var matcher = heading.matcher(text.substring(0, start + Math.min(content.length(), 180)));
      while (matcher.find()) section = matcher.group().replaceFirst("^#+\\s*", "");
      int page = 1;
      for (int i = 0; i < start; i++) if (text.charAt(i) == '\f') page++;
      if (!content.isBlank())
        result.add(
            new Part(
                result.size() + 1,
                section.substring(0, Math.min(200, section.length())),
                text.indexOf('\f') >= 0 ? page : null,
                start,
                end,
                content));
      if (end == text.length()) break;
      start = end == pageBreak + 1 ? end : Math.max(start + 1, end - 140);
    }
    Problem.require(result.size() <= 160, "文档分段过多，请拆分上传");
    return result;
  }
}
