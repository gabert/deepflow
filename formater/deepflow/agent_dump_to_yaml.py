import os

from deepflow import preprocessor, metadata_strip
from deepflow.line_formater import FormaterFactory

delimiter = ";"


def process_session(directory):
    for filename in os.listdir(directory):
        if filename.endswith('.dmp'):
            dump_file_path = os.path.join(directory, filename)
            base_name = os.path.splitext(filename)[0]
            # base_name = os.path.splitext(dump_file_path)[0]
            yaml_file_path = f'{base_name}.yaml'
            process_dump_file(dump_file_path, yaml_file_path)


def process_dump_file(dump_file_path, yaml_file_path):
    dump_file = open_file(dump_file_path)
    yaml_file = open_file(yaml_file_path, 'w')

    base_formater = FormaterFactory.get_formater('yaml')

    for dump_line in dump_file:
        yaml_entries = process_dump_line(dump_line.rstrip('\n'), base_formater)
        for entry in yaml_entries:
            yaml_file.write(f'{entry}\n')

    dump_file.close()
    yaml_file.close()


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
    data_hashed = preprocessor.hash_update(json_string)

    record['raw_data'] = metadata_strip.extract_data(data_hashed)
    record['meta_data'] = metadata_strip.extract_metadata(data_hashed)

    return record


def open_file(filename, mode='r'):
    file = open(filename, mode)
    return file


if __name__ == '__main__':
    process_session('D:\\temp\\SESSION-20240601_150303')
