import os
import zipfile

from deepflow import hasher, metadata_strip
from deepflow.line_formater import FormaterFactory

delimiter = ";"


class LineProcessor:
    def __init__(self, base_formater):
        self.depth = -1
        self.method_id = -1
        self.parent_stack = []
        self.base_formater = base_formater

    def process_dump_line(self, line):
        record = self.__parse_line(line)

        if record['type'] == "AR" or record['type'] == "RE":
            record = self.__compute_hash(record)

        return record

    def __parse_line(self, line):
        parts = line.split(delimiter)

        record_type = parts[0]
        value = ";".join(parts[1:])

        parent_method_id = self.parent_stack[-1] if self.parent_stack else None

        if record_type == 'MS':
            self.depth += 1
            self.method_id += 1
            self.parent_stack.append(self.method_id)

        record = {
            "type": record_type,
            "value": value,
            "depth": self.depth,
            "method_id": self.method_id,
            "parent_method_id": parent_method_id,
        }

        if record_type == 'ME':
            self.depth -= 1
            self.parent_stack.pop()

        return record

    def __compute_hash(self, record):
        json_string = record['value']
        data_hashed = hasher.hash_update(json_string)

        record['raw_data'] = metadata_strip.extract_data(data_hashed)
        record['meta_data'] = metadata_strip.extract_metadata(data_hashed)

        return record


def process_session(directory, destination_format='yaml', compress=True):
    for filename in os.listdir(directory):
        if not filename.endswith('.dft') and not filename.endswith('.dfz'):
            continue

        dump_file_path = os.path.join(directory, filename)
        base_name = os.path.splitext(dump_file_path)[0]
        dst_file_path = f'{base_name}.{destination_format}'

        if filename.endswith('.dft'):
            process_dump_txt_file(dump_file_path, destination_format, dst_file_path, compress)
        elif filename.endswith('.dfz'):
            process_dump_bin_file(dump_file_path, destination_format, dst_file_path, compress)


def process_dump_txt_file(dump_file_path, destination_format, dst_file_path, compress):
    with open(dump_file_path, 'r') as dump_source:
        dst_file = open_destination_file(dst_file_path, compress)
        with dst_file:
            base_formater = FormaterFactory.get_formater(destination_format)

            if compress:
                output_file_name = os.path.basename(dst_file_path)
                with dst_file.open(output_file_name, 'w') as output_file:
                    process_dump_data(dump_source,
                                      output_file,
                                      base_formater,
                                      compress,
                                      dump_file_path.endswith('.dfz'))
            else:
                process_dump_data(dump_source,
                                  dst_file,
                                  base_formater,
                                  compress,
                                  dump_file_path.endswith('.dfz'))


def process_dump_bin_file(dump_file_path, destination_format, dst_file_path, compress):
    with zipfile.ZipFile(dump_file_path, 'r') as input_zip:
        with input_zip.open(input_zip.namelist()[0]) as dump_source:
            dst_file = open_destination_file(dst_file_path, compress)
            with dst_file:
                base_formater = FormaterFactory.get_formater(destination_format)

                if compress:
                    output_file_name = input_zip.namelist()[0].replace(".dft", f'.{destination_format}')
                    with dst_file.open(output_file_name, 'w') as output_file:
                        process_dump_data(dump_source,
                                          output_file,
                                          base_formater,
                                          compress,
                                          dump_file_path.endswith('.dfz'))
                else:
                    process_dump_data(dump_source,
                                      dst_file,
                                      base_formater,
                                      compress,
                                      dump_file_path.endswith('.dfz'))


def process_dump_data(dump_source, output_file, base_formater, compress: bool, decode: bool):
    line_processor = LineProcessor(base_formater)
    for dump_line in dump_source:
        line = dump_line.decode('utf-8') if decode else dump_line
        record = line_processor.process_dump_line(line.rstrip('\n').rstrip('\r'))
        entries = base_formater.format(record)
        for entry in entries:
            entry = f'{entry}\n'.encode("utf-8") if compress else f'{entry}\n'
            output_file.write(entry)


def open_destination_file(dst_file_path, compress):
    if compress:
        dst_file_path = f'{dst_file_path}.zip'
        dst_file = zipfile.ZipFile(dst_file_path, 'w', compression=zipfile.ZIP_DEFLATED)
    else:
        dst_file = open(dst_file_path, 'w')

    return dst_file


if __name__ == '__main__':
    process_session('D:\\temp\\SESSION-20240605-143537',
                    destination_format='yaml',
                    compress=False)
    # process_session('D:\\temp\\SESSION-20240603-221554',
    #                 destination_format='yaml',
    #                 compress=False)
