import os
import gzip
import zlib

from deepflow import hasher, metadata_strip
from deepflow.line_formater import FormaterFactory

delimiter = ";"


def process_session(directory, destination_format='yaml', compress=True):
    for filename in os.listdir(directory):
        if not filename.endswith('.dft') and not filename.endswith('.dfb'):
            continue

        dump_file_path = os.path.join(directory, filename)
        base_name = os.path.splitext(filename)[0]
        # base_name = os.path.splitext(dump_file_path)[0]
        dst_file_path = f'{base_name}.{destination_format}'

        if filename.endswith('.dft'):
            process_dump_txt_file(dump_file_path, destination_format, dst_file_path, compress)
        elif filename.endswith('.dfb'):
            process_dump_bin_file(dump_file_path, destination_format, dst_file_path, compress)


def process_dump_txt_file(dump_file_path, destination_format, dst_file_path, compress):
    with open(dump_file_path, 'r') as dump_source:
        dst_file = open_destination_file(dst_file_path, compress)

        with dst_file:
            base_formater = FormaterFactory.get_formater(destination_format)
            dump_entries(dump_source, dst_file, base_formater)


def process_dump_bin_file(dump_file_path, destination_format, dst_file_path, compress):
    with open(dump_file_path, 'rb') as dump_file:
        dst_file = open_destination_file(dst_file_path, compress)

        with dst_file:
            base_formater = FormaterFactory.get_formater(destination_format)
            while True:
                length_bytes = dump_file.read(4)
                if not length_bytes:
                    break
                entry_length = int.from_bytes(length_bytes, byteorder='big')
                compressed_data = dump_file.read(entry_length)
                dump_lines = decompress_string(compressed_data)

                dump_entries(dump_lines.splitlines(), dst_file, base_formater)


def open_destination_file(dst_file_path, compress):
    if compress:
        dst_file_path = f'{dst_file_path}.gz'
        dst_file = gzip.open(dst_file_path, 'at', compresslevel=9)
    else:
        dst_file = open(dst_file_path, 'w')

    return dst_file


def dump_entries(dump_source, dst_file, base_formater):
    for dump_line in dump_source:
        entries = process_dump_line(dump_line.rstrip('\n').rstrip('\r'), base_formater)
        for entry in entries:
            dst_file.write(f'{entry}\n')


def decompress_string(compressed_data):
    try:
        return zlib.decompress(compressed_data).decode('utf-8')
    except zlib.error:
        try:
            return zlib.decompress(compressed_data, -zlib.MAX_WBITS).decode('utf-8')
        except zlib.error as e:
            print("Error decompressing data: ", e)
            return None


def process_dump_line(line, base_formater):
    record = parse_line(line)

    if record['type'] == "AR" or record['type'] == "RE":
        record = compute_hash(record)

    file_entries = base_formater.format(record)

    return file_entries


def parse_line(line):
    parts = line.split(delimiter)

    return {
        "depth": int(parts[0]),
        "thread": parts[1],
        "type": parts[2],
        "value": ";".join(parts[3:])
    }


def compute_hash(record):
    json_string = record['value']
    data_hashed = hasher.hash_update(json_string)

    record['raw_data'] = metadata_strip.extract_data(data_hashed)
    record['meta_data'] = metadata_strip.extract_metadata(data_hashed)

    return record


if __name__ == '__main__':
    process_session('D:\\temp\\SESSION-20240602_172908',
                    destination_format='yaml',
                    compress=False)
    # process_session('D:\\temp\\SESSION-20240602_173153',
    #                 destination_format='yaml',
    #                 compress=False)
