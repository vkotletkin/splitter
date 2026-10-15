package ru.kotletkin.splitter;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
public class SplitController {

    private static final byte[] LF_SEPARATOR = "\n\n".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] CRLF_SEPARATOR = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private static List<HttpMessage> splitMessages(byte[] data) {
        try {
            return HttpStreamSplitter.split(data);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private static byte[] toZip(List<HttpMessage> messages) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            int width = Math.max(3, String.valueOf(messages.size()).length());
            String pattern = "%0" + width + "d.http";
            for (int index = 0; index < messages.size(); index++) {
                zip.putNextEntry(new ZipEntry(pattern.formatted(index + 1)));
                zip.write(messageBytes(messages.get(index)));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static byte[] messageBytes(HttpMessage message) {
        byte[] headers = message.headers();
        byte[] body = message.body();
        byte[] separator = usesCarriageReturn(headers) ? CRLF_SEPARATOR : LF_SEPARATOR;
        byte[] raw = new byte[headers.length + separator.length + body.length];
        System.arraycopy(headers, 0, raw, 0, headers.length);
        System.arraycopy(separator, 0, raw, headers.length, separator.length);
        System.arraycopy(body, 0, raw, headers.length + separator.length, body.length);
        return raw;
    }

    private static boolean usesCarriageReturn(byte[] headers) {
        for (int index = 0; index < headers.length - 1; index++) {
            if (headers[index] == '\r' && headers[index + 1] == '\n') {
                return true;
            }
        }
        return false;
    }

    private static String zipName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "requests.zip";
        }
        String name = originalName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) {
            return "requests.zip";
        }
        return safe + ".zip";
    }

    @PostMapping(path = "/api/split", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/zip")
    public ResponseEntity<byte[]> split(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл пуст");
        }
        List<HttpMessage> messages = splitMessages(file.getBytes());
        if (messages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "В файле нет HTTP-запросов");
        }
        return ResponseEntity.ok()
                .headers(headers -> headers.setContentDisposition(ContentDisposition.attachment()
                        .filename(zipName(file.getOriginalFilename()))
                        .build()))
                .contentType(ZIP)
                .body(toZip(messages));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/zip")
    public void test(@RequestParam("file") MultipartFile file) throws IOException {
        List<HttpMessage> messages = splitMessages(file.getBytes());
        ParsedHttpRequest parsedHttpRequest = HttpMessageParser.parse(messages.getFirst());
        System.out.println();
    }

}
