package ru.kotletkin.splitter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SplitController.class)
class SplitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsEachRequestAsFileInsideZip() throws Exception {
        byte[] source = Files.readAllBytes(Path.of("data/example.http"));
        List<HttpMessage> expected = HttpStreamSplitter.split(source);

        byte[] zip = mockMvc.perform(multipart("/api/split")
                        .file(new MockMultipartFile("file", "example.http", "application/octet-stream", source)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"example.zip\""))
                .andExpect(header().string("Content-Type", "application/zip"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        List<byte[]> files = unzip(zip);
        assertEquals(expected.size(), files.size());
        assertEquals(45, files.size());
        for (int index = 0; index < expected.size(); index++) {
            List<HttpMessage> restored = HttpStreamSplitter.split(files.get(index));
            assertEquals(1, restored.size(), "файл " + (index + 1));
            assertEquals(expected.get(index), restored.get(0));
        }
        assertTrue(new String(files.get(0), StandardCharsets.ISO_8859_1).startsWith("GET "));
    }

    @Test
    void packsBinaryBodiesWithoutCuttingThem() throws Exception {
        byte[] source = Files.readAllBytes(Path.of("data/binary-stream.http"));

        byte[] zip = mockMvc.perform(multipart("/api/split")
                        .file(new MockMultipartFile("file", "binary-stream.http", "application/octet-stream", source)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        List<byte[]> files = unzip(zip);
        assertEquals(18, files.size());
        byte[] png = HttpStreamSplitter.split(files.get(0)).get(0).body();
        assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, Arrays.copyOf(png, 4));
    }

    @Test
    void rejectsUnreadableStream() throws Exception {
        byte[] broken = "POST /a HTTP/1.1\nHost: test.ru\nContent-Length: 10\n\nhi".getBytes(StandardCharsets.US_ASCII);

        mockMvc.perform(multipart("/api/split")
                        .file(new MockMultipartFile("file", "broken.http", "application/octet-stream", broken)))
                .andExpect(status().isBadRequest());
    }

    private static List<byte[]> unzip(byte[] zip) throws IOException {
        List<byte[]> files = new ArrayList<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            while (input.getNextEntry() != null) {
                files.add(input.readAllBytes());
            }
        }
        return files;
    }

}
