package com.jot.notes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes a minimal but valid .docx (pure Java). Parts are markdown strings or PNG images. */
public final class DocxWriter {

    public static final class Image {
        public final byte[] png; public final int w, h; public final String ext;
        public Image(byte[] png, int w, int h) { this(png, w, h, "png"); }
        public Image(byte[] data, int w, int h, String ext) { this.png = data; this.w = w; this.h = h; this.ext = ext; }
    }

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    public static void write(String title, List<Object> parts, OutputStream os) throws IOException {
        StringBuilder body = new StringBuilder();
        List<String> rels = new ArrayList<>();
        List<byte[]> media = new ArrayList<>();
        List<String> exts = new ArrayList<>();

        if (title != null && !title.trim().isEmpty()) {
            body.append("<w:p><w:pPr><w:pStyle w:val=\"Title\"/></w:pPr>").append(run(title, 0, null)).append("</w:p>");
        }
        int img = 0;
        for (Object part : parts) {
            if (part instanceof String) {
                for (Md.Block b : Md.toBlocks((String) part)) body.append(paragraph(b, rels));
            } else if (part instanceof Image) {
                Image im = (Image) part;
                img++;
                media.add(im.png);
                exts.add(im.ext);
                String rid = "rIdImg" + img;
                rels.add("<Relationship Id=\"" + rid + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/sketch" + img + "." + im.ext + "\"/>");
                long cx = 5486400L; // 6 inches
                long cy = Math.max(1, cx * im.h / Math.max(1, im.w));
                if (cy > 8229600L) { cx = cx * 8229600L / cy; cy = 8229600L; } // max 9 inches tall
                body.append("<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
                    .append("<wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
                    .append("<wp:docPr id=\"").append(img).append("\" name=\"Sketch ").append(img).append("\"/>")
                    .append("<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
                    .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>")
                    .append("<pic:nvPicPr><pic:cNvPr id=\"").append(img).append("\" name=\"sketch").append(img).append(".").append(im.ext).append("\"/><pic:cNvPicPr/></pic:nvPicPr>")
                    .append("<pic:blipFill><a:blip r:embed=\"").append(rid).append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
                    .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/></a:xfrm>")
                    .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>")
                    .append("</wp:inline></w:drawing></w:r></w:p>");
            }
        }

        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<w:document xmlns:w=\"" + W + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
            + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
            + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
            + " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
            + body
            + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>"
            + "</w:body></w:document>";

        StringBuilder docRels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        for (String r : rels) docRels.append(r);
        docRels.append("</Relationships>");

        ZipOutputStream z = new ZipOutputStream(os);
        put(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
            + "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
            + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
            + "</Types>");
        put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
            + "</Relationships>");
        put(z, "word/document.xml", document);
        put(z, "word/_rels/document.xml.rels", docRels.toString());
        put(z, "word/styles.xml", styles());
        for (int i = 0; i < media.size(); i++) {
            z.putNextEntry(new ZipEntry("word/media/sketch" + (i + 1) + "." + exts.get(i)));
            z.write(media.get(i));
            z.closeEntry();
        }
        z.finish();
    }

    private static String paragraph(Md.Block b, List<String> rels) {
        StringBuilder p = new StringBuilder("<w:p><w:pPr>");
        String prefix = null;
        int indentTw = 0;
        switch (b.type) {
            case Md.H: p.append("<w:pStyle w:val=\"Heading").append(Math.min(b.level, 6)).append("\"/>"); break;
            case Md.QUOTE: p.append("<w:pStyle w:val=\"Quote\"/>"); break;
            case Md.CODEBLOCK: p.append("<w:pStyle w:val=\"Code\"/>"); break;
            case Md.BULLET: prefix = "•"; indentTw = 360 * (b.level + 1); break;
            case Md.NUMBER: prefix = b.number + "."; indentTw = 360 * (b.level + 1); break;
            case Md.TASK: prefix = b.checked ? "☒" : "☐"; indentTw = 360 * (b.level + 1); break;
            case Md.HR: p.append("<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"BBBBBB\"/></w:pBdr>"); break;
            default: break;
        }
        if (indentTw > 0) p.append("<w:tabs><w:tab w:val=\"left\" w:pos=\"").append(indentTw).append("\"/></w:tabs>")
                .append("<w:ind w:left=\"").append(indentTw).append("\" w:hanging=\"360\"/>");
        p.append("</w:pPr>");
        if (prefix != null) p.append(run(prefix, 0, null)).append("<w:r><w:tab/></w:r>");
        for (Md.Run r : b.runs) {
            int st = r.style;
            if (b.type == Md.TASK && b.checked) st |= Md.STRIKE;
            if ((st & Md.LINK) != 0 && r.url != null && r.url.matches("(?i)(https?|mailto):.*")) {
                String rid = "rIdLink" + (rels.size() + 1);
                rels.add("<Relationship Id=\"" + rid + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\""
                        + esc(r.url) + "\" TargetMode=\"External\"/>");
                p.append("<w:hyperlink r:id=\"").append(rid).append("\">").append(run(r.text, st, "2F6F5E")).append("</w:hyperlink>");
            } else {
                p.append(run(r.text, st, (st & Md.LINK) != 0 ? "2F6F5E" : null));
            }
        }
        return p.append("</w:p>").toString();
    }

    private static String run(String text, int st, String color) {
        StringBuilder r = new StringBuilder("<w:r><w:rPr>");
        if ((st & Md.CODE) != 0) r.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>");
        if ((st & Md.BOLD) != 0) r.append("<w:b/>");
        if ((st & Md.ITALIC) != 0) r.append("<w:i/>");
        if ((st & Md.STRIKE) != 0) r.append("<w:strike/>");
        if (color != null) r.append("<w:color w:val=\"").append(color).append("\"/>");
        if ((st & Md.HIGHLIGHT) != 0) r.append("<w:highlight w:val=\"yellow\"/>");
        if ((st & (Md.UNDERLINE | Md.LINK)) != 0) r.append("<w:u w:val=\"single\"/>");
        if ((st & Md.CODE) != 0) r.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F0EFEA\"/>");
        r.append("</w:rPr><w:t xml:space=\"preserve\">").append(esc(text)).append("</w:t></w:r>");
        return r.toString();
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default:
                    if (c < 0x20 && c != '\t') continue; // invalid in XML
                    b.append(c);
            }
        }
        return b.toString();
    }

    private static String styles() {
        StringBuilder s = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles xmlns:w=\"" + W + "\">"
            + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>"
            + "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:rPrDefault>"
            + "<w:pPrDefault><w:pPr><w:spacing w:after=\"80\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
            + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"240\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"44\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"8\" w:color=\"2F6F5E\"/></w:pBdr><w:ind w:left=\"360\"/></w:pPr><w:rPr><w:i/><w:color w:val=\"555555\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Code\"><w:name w:val=\"Code\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"0\"/><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F0EFEA\"/></w:pPr><w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/><w:sz w:val=\"20\"/></w:rPr></w:style>");
        int[] sizes = {0, 36, 30, 26, 24, 22, 22};
        for (int i = 1; i <= 6; i++) {
            s.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading").append(i).append("\"><w:name w:val=\"heading ").append(i)
             .append("\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/><w:outlineLvl w:val=\"")
             .append(i - 1).append("\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"").append(sizes[i]).append("\"/></w:rPr></w:style>");
        }
        return s.append("</w:styles>").toString();
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes("UTF-8"));
        z.closeEntry();
    }

    public static byte[] toBytes(String title, List<Object> parts) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        write(title, parts, bo);
        return bo.toByteArray();
    }
}
