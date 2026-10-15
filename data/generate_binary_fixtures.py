#!/usr/bin/env python3
"""Собирает бинарные фикстуры и HTTP-поток data/binary-stream.http.

Тела — настоящие PNG, JPEG, GIF, BMP, PDF (FlateDecode), ZIP, tar.gz,
DOCX (OOXML) и DOC (Word через textutil). Внутри есть упоминания
PUT /client/15 без стартовой строки HTTP, плюс запросы, приклеенные к телу.
"""

import gzip
import io
import struct
import subprocess
import tarfile
import zipfile
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BIN = ROOT / "binaries"
DECOY = b"PUT /client/15\nPOST /archive/contracts\n"
FIXED_TIME = (2026, 10, 15, 12, 0, 0)


def png_chunk(tag: bytes, data: bytes) -> bytes:
    crc = zlib.crc32(tag + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)


def build_png() -> bytes:
    width, height = 16, 16
    rows = []
    for y in range(height):
        row = bytearray()
        for x in range(width):
            row += bytes((x * 15, y * 15, (x * y) % 256))
        rows.append(b"\x00" + bytes(row))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    text = b"Comment\x00" + DECOY
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", ihdr)
        + png_chunk(b"tEXt", text)
        + png_chunk(b"IDAT", zlib.compress(b"".join(rows), 9))
        + png_chunk(b"IEND", b"")
    )


def build_gif() -> bytes:
    # 1x1 GIF89a, comment extension inserted before the image descriptor.
    base = bytes.fromhex(
        "47494638396101000100800000ffffff000000"
        "21f9040100000000"
        "2c000000000100010000"
        "0202440100"
        "3b"
    )
    comment = b"\x21\xfe" + bytes([len(DECOY)]) + DECOY + b"\x00"
    marker = base.index(b"\x2c")
    return base[:marker] + comment + base[marker:]


def build_bmp() -> bytes:
    width, height = 8, 8
    row_stride = (width * 3 + 3) & ~3
    pixels = bytearray()
    for y in range(height):
        row = b"".join(bytes((180, x * 30, y * 30)) for x in range(width))
        pixels += row + b"\x00" * (row_stride - width * 3)
    comment = b"BMP gap\n" + DECOY
    pixel_offset = 14 + 40 + len(comment)
    size = pixel_offset + len(pixels)
    file_header = struct.pack("<2sIHHI", b"BM", size, 0, 0, pixel_offset)
    dib = struct.pack("<IiiHHIIiiII", 40, width, height, 1, 24, 0, len(pixels), 2835, 2835, 0, 0)
    return file_header + dib + comment + bytes(pixels)


def build_pdf() -> bytes:
    text = b"Mention PUT /client/15 inside a real PDF stream"
    stream = zlib.compress(b"BT /F1 18 Tf 72 700 Td (" + text + b") Tj ET")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Count 1 /Kids [3 0 R] >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        b"<< /Length %d /Filter /FlateDecode >>\nstream\n" % len(stream) + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    out += b"% Mention: PUT /client/15\n% Archive: POST /archive/contracts\n"
    offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(out))
        out += f"{index} 0 obj\n".encode("ascii") + obj + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n".encode("ascii")
    out += b"0000000000 65535 f \n"
    for offset in offsets[1:]:
        out += f"{offset:010d} 00000 n \n".encode("ascii")
    out += (
        f"trailer<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"
    ).encode("ascii")
    return bytes(out)


def build_jpeg(png_path: Path, dest: Path) -> None:
    subprocess.run(
        ["sips", "-s", "format", "jpeg", str(png_path), "--out", str(dest)],
        check=True,
        capture_output=True,
    )
    jpeg = dest.read_bytes()
    if jpeg[:3] != b"\xff\xd8\xff":
        raise SystemExit("sips did not produce a JPEG")
    comment = b"JPEG COM " + DECOY
    segment = b"\xff\xfe" + struct.pack(">H", len(comment) + 2) + comment
    dest.write_bytes(jpeg[:2] + segment + jpeg[2:])


def zip_info(name: str, compress_type: int) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, FIXED_TIME)
    info.compress_type = compress_type
    info.create_system = 0
    info.external_attr = 0o644 << 16
    return info


def build_zip(png: bytes, pdf: bytes) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr(zip_info("note.txt", zipfile.ZIP_STORED), b"Note\n" + DECOY)
        archive.writestr(zip_info("photo.png", zipfile.ZIP_DEFLATED), png)
        archive.writestr(zip_info("report.pdf", zipfile.ZIP_DEFLATED), pdf)
    return buffer.getvalue()


def build_tar_gz(gif: bytes) -> bytes:
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w") as archive:
        note = b"tar note\n" + DECOY
        note_info = tarfile.TarInfo("note.txt")
        note_info.size = len(note)
        note_info.mtime = 1760500800
        archive.addfile(note_info, io.BytesIO(note))
        gif_info = tarfile.TarInfo("picture.gif")
        gif_info.size = len(gif)
        gif_info.mtime = 1760500800
        archive.addfile(gif_info, io.BytesIO(gif))
    return gzip.compress(raw.getvalue(), compresslevel=9, mtime=1760500800)


def build_docx(png: bytes) -> bytes:
    document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    <w:p><w:r><w:t>Договор поставки №15. Служебная команда для изменения клиента: PUT /client/15</w:t></w:r></w:p>
    <w:p><w:r><w:t>Команда отправки документа в архив: POST /archive/contracts</w:t></w:r></w:p>
  </w:body>
</w:document>
""".encode("utf-8")
    content_types = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="png" ContentType="image/png"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
  <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>
""".encode("utf-8")
    rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>
""".encode("utf-8")
    document_rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/photo.png"/>
</Relationships>
""".encode("utf-8")
    core = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/">
  <dc:title>Договор поставки</dc:title>
  <dc:creator>BinaryFixture</dc:creator>
</cp:coreProperties>
""".encode("utf-8")
    app = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
  <Application>Microsoft Office Word</Application>
</Properties>
""".encode("utf-8")
    parts = {
        "[Content_Types].xml": content_types,
        "_rels/.rels": rels,
        "word/document.xml": document,
        "word/_rels/document.xml.rels": document_rels,
        "word/media/photo.png": png,
        "docProps/core.xml": core,
        "docProps/app.xml": app,
    }
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, payload in parts.items():
            archive.writestr(zip_info(name, zipfile.ZIP_DEFLATED), payload)
    return buffer.getvalue()


def build_doc(docx_text: str, dest: Path) -> None:
    source = BIN / "legacy-source.txt"
    source.write_text(docx_text, encoding="utf-8")
    subprocess.run(
        ["textutil", "-convert", "doc", str(source), "-output", str(dest)],
        check=True,
        capture_output=True,
    )


def http_message(request_line: str, content_type: str, body: bytes) -> bytes:
    head = (
        f"{request_line}\r\n"
        f"Host: test.ru\r\n"
        f"User-Agent: BinaryFixture/1.0\r\n"
        f"Content-Type: {content_type}\r\n"
        f"Content-Length: {len(body)}\r\n"
        f"\r\n"
    )
    return head.encode("ascii") + body


def client_put(client_id: int) -> tuple[str, bytes]:
    body = f'{{"clientId":{client_id},"operation":"update","source":"embedded"}}'.encode("ascii")
    request = f"PUT /client/{client_id} HTTP/1.1"
    return request, http_message(request, "application/json; charset=utf-8", body)


def glued(request_line: str, content_type: str, file_bytes: bytes, embedded: bytes) -> bytes:
    """Content-Length завышен и включает следующий запрос, приклеенный к телу."""
    payload = file_bytes + embedded
    head = (
        f"{request_line}\r\n"
        f"Host: test.ru\r\n"
        f"User-Agent: BinaryFixture/1.0\r\n"
        f"Content-Type: {content_type}\r\n"
        f"Content-Length: {len(payload)}\r\n"
        f"\r\n"
    )
    return head.encode("ascii") + payload


def main() -> None:
    BIN.mkdir(parents=True, exist_ok=True)
    png = build_png()
    gif = build_gif()
    bmp = build_bmp()
    pdf = build_pdf()
    (BIN / "sample.png").write_bytes(png)
    (BIN / "sample.gif").write_bytes(gif)
    (BIN / "sample.bmp").write_bytes(bmp)
    (BIN / "sample.pdf").write_bytes(pdf)
    build_jpeg(BIN / "sample.png", BIN / "sample.jpg")
    jpeg = (BIN / "sample.jpg").read_bytes()
    archive = build_zip(png, pdf)
    tar_gz = build_tar_gz(gif)
    docx = build_docx(png)
    (BIN / "sample.zip").write_bytes(archive)
    (BIN / "sample.tar.gz").write_bytes(tar_gz)
    (BIN / "sample.docx").write_bytes(docx)
    legacy_text = (
        "Договор поставки №15\n"
        "Служебная команда для изменения клиента: PUT /client/15\n"
        "Команда отправки документа в архив: POST /archive/contracts\n"
    )
    build_doc(legacy_text, BIN / "sample.doc")
    (BIN / "legacy-source.txt").unlink()
    doc = (BIN / "sample.doc").read_bytes()

    files = {
        "sample.png": png,
        "sample.jpg": jpeg,
        "sample.gif": gif,
        "sample.bmp": bmp,
        "sample.pdf": pdf,
        "sample.zip": archive,
        "sample.tar.gz": tar_gz,
        "sample.docx": docx,
        "sample.doc": doc,
    }
    for name, payload in files.items():
        if b"HTTP/1." in payload:
            raise SystemExit(f"{name} unexpectedly contains HTTP/1.")

    put41_line, put41 = client_put(41)
    put42_line, put42 = client_put(42)
    put43_line, put43 = client_put(43)
    put44_line, put44 = client_put(44)
    done_body = b'{"status":"ok"}'
    done_line = "POST http://test.ru/upload/done HTTP/1.1"

    sep = b"\r\n\r\n"
    parts = [
        http_message("POST http://test.ru/upload/photo.png HTTP/1.1", "image/png", png),
        sep,
        http_message("POST http://test.ru/upload/photo.jpg HTTP/1.1", "image/jpeg", jpeg),
        sep,
        http_message("POST http://test.ru/upload/picture.gif HTTP/1.1", "image/gif", gif),
        sep,
        http_message("POST http://test.ru/upload/picture.bmp HTTP/1.1", "image/bmp", bmp),
        sep,
        http_message("POST http://test.ru/upload/report.pdf HTTP/1.1", "application/pdf", pdf),
        sep,
        http_message("POST http://test.ru/upload/bundle.zip HTTP/1.1", "application/zip", archive),
        sep,
        http_message("POST http://test.ru/upload/bundle.tar.gz HTTP/1.1", "application/gzip", tar_gz),
        sep,
        http_message(
            "POST http://test.ru/upload/report.docx HTTP/1.1",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            docx,
        ),
        sep,
        http_message("POST http://test.ru/upload/legacy.doc HTTP/1.1", "application/msword", doc),
        sep,
        glued("POST http://test.ru/upload/scan.jpg HTTP/1.1", "image/jpeg", jpeg, put41),
        sep,
        glued(
            "PUT http://test.ru/upload/contract.docx HTTP/1.1",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            docx,
            put42,
        ),
        sep,
        glued("PUT http://test.ru/upload/page.pdf HTTP/1.1", "application/pdf", pdf, put43),
        sep,
        http_message("POST http://test.ru/upload/archive.zip HTTP/1.1", "application/zip", archive),
        put44,
        sep,
        http_message(done_line, "application/json; charset=utf-8", done_body),
    ]
    (ROOT / "binary-stream.http").write_bytes(b"".join(parts))

    rows = [
        ("POST http://test.ru/upload/photo.png HTTP/1.1", "file", "sample.png"),
        ("POST http://test.ru/upload/photo.jpg HTTP/1.1", "file", "sample.jpg"),
        ("POST http://test.ru/upload/picture.gif HTTP/1.1", "file", "sample.gif"),
        ("POST http://test.ru/upload/picture.bmp HTTP/1.1", "file", "sample.bmp"),
        ("POST http://test.ru/upload/report.pdf HTTP/1.1", "file", "sample.pdf"),
        ("POST http://test.ru/upload/bundle.zip HTTP/1.1", "file", "sample.zip"),
        ("POST http://test.ru/upload/bundle.tar.gz HTTP/1.1", "file", "sample.tar.gz"),
        ("POST http://test.ru/upload/report.docx HTTP/1.1", "file", "sample.docx"),
        ("POST http://test.ru/upload/legacy.doc HTTP/1.1", "file", "sample.doc"),
        ("POST http://test.ru/upload/scan.jpg HTTP/1.1", "file", "sample.jpg"),
        (put41_line, "utf8", put41.split(b"\r\n\r\n", 1)[1].decode("ascii")),
        ("PUT http://test.ru/upload/contract.docx HTTP/1.1", "file", "sample.docx"),
        (put42_line, "utf8", put42.split(b"\r\n\r\n", 1)[1].decode("ascii")),
        ("PUT http://test.ru/upload/page.pdf HTTP/1.1", "file", "sample.pdf"),
        (put43_line, "utf8", put43.split(b"\r\n\r\n", 1)[1].decode("ascii")),
        ("POST http://test.ru/upload/archive.zip HTTP/1.1", "file", "sample.zip"),
        (put44_line, "utf8", put44.split(b"\r\n\r\n", 1)[1].decode("ascii")),
        (done_line, "utf8", done_body.decode("ascii")),
    ]
    (BIN / "expected.tsv").write_text(
        "".join(f"{line}\t{kind}\t{value}\n" for line, kind, value in rows),
        encoding="utf-8",
    )
    print(f"stream bytes: {(ROOT / 'binary-stream.http').stat().st_size}")
    print(f"messages: {len(rows)}")


if __name__ == "__main__":
    main()
