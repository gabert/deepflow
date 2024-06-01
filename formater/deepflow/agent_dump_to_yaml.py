import yaml
import os

from deepflow import preprocessor, metadata_strip

delimiter = ";"
indent = 4


def process_session(directory):
    for filename in os.listdir(directory):
        if filename.endswith('.dmp'):
            dump_file_path = os.path.join(directory, filename)
            base_name = os.path.splitext(filename)[0]
            # base_name = os.path.splitext(dump_file_path)[0]
            yaml_file_path = f'{base_name}.yaml'
            process_dump_file(dump_file_path, yaml_file_path)


def process_dump_file(dump_file_path, yaml_file_path):
    yaml_file = open_file(yaml_file_path, 'a')
    dump_file = open_file(dump_file_path)

    previous_level = -1

    for dump_line in dump_file:
        yaml_entries, previous_level = process_dump_line(dump_line.rstrip('\n'), previous_level)
        for entry in yaml_entries:
            yaml_file.write(f'{entry}\n')

    dump_file.close()
    yaml_file.close()


def process_dump_line(line, previous_level):
    record_formats = {
        "MS": lambda rec: format_ms(rec, previous_level),
        "TS": lambda rec: format_ts(rec, 'TS'),
        "TE": lambda rec: format_ts(rec, 'TE'),
        "AR": lambda rec: format_ar(rec),
        "RE": lambda rec: format_re(rec)
    }

    fields = split_line(line)
    record = parse_fields(fields)
    yaml_entries = record_formats.get(record["type"], lambda _: [])(record)

    return yaml_entries, record['depth']


def split_line(line):
    parts = line.split(delimiter)
    depth = parts[0]
    thread = parts[1]
    record_type = parts[2]
    value = ";".join(parts[3:])

    return depth, thread, record_type, value


def parse_fields(fields):
    return {
        "depth": int(fields[0]),
        "thread": fields[1],
        "type": fields[2],
        "value": fields[3]
    }


# def parse_fields_ts(fields):
#     return {
#         "depth": int(fields[0]),
#         "thread": fields[1],
#         "type": fields[2],
#         "value": fields[3]
#     }


def format_ms(record, previous_level):
    data = record['value']
    offset_string = ' ' * indent_size(record['type'], record['depth'])
    yaml_lines = []

    if record['depth'] > previous_level:
        yaml_lines.append(offset_string + 'CS:')

    yaml_lines.append(f"{offset_string}{(' ' * indent).replace(' ', '-', 1)}'{data}':")
    return yaml_lines


def format_ts(record, tag):
    data = record['value']
    offset_string = ' ' * indent_size(record['type'], record['depth'])
    yaml_lines = []
    yaml_lines.append(f"{offset_string}{tag}: '{data}'")
    return yaml_lines


def format_ar(record):
    json_string = record['value']
    data_hashed = preprocessor.hash_update(json_string)
    raw_data = metadata_strip.extract_data(data_hashed)
    meta_data = metadata_strip.extract_metadata(data_hashed)

    raw_data_yaml_lines = convert_to_yaml(raw_data, "AR", record['type'], record['depth'])
    meta_data_yaml_lines = convert_to_yaml(meta_data, "AI", record['type'], record['depth'])

    yaml_lines = raw_data_yaml_lines + meta_data_yaml_lines

    return yaml_lines


def convert_to_yaml(data, tag, record_type, depth):
    tagged_object = {tag: data}
    yaml_string = yaml.dump(tagged_object, indent=indent, sort_keys=True).rstrip('\n')

    offset_string = ' ' * indent_size(record_type, depth)
    yaml_lines = []
    for yaml_line in yaml_string.split("\n"):
        yaml_lines.append(f'{offset_string}{yaml_line}')
    return yaml_lines


def format_re(record):
    json_string = record['value']
    data_hashed = preprocessor.hash_update(json_string)
    raw_data = metadata_strip.extract_data(data_hashed)
    meta_data = metadata_strip.extract_metadata(data_hashed)

    raw_data_yaml_lines = convert_to_yaml(raw_data, "RE", record['type'], record['depth'])
    meta_data_yaml_lines = convert_to_yaml(meta_data, "RI", record['type'], record['depth'])

    yaml_lines = raw_data_yaml_lines + meta_data_yaml_lines

    return yaml_lines


def indent_size(record_type, depth):
    if record_type == 'MS':
        return depth * (2 * indent)
    else:
        return (depth + 1) * (2 * indent)


def open_file(filename, mode='r'):
    try:
        file = open(filename, mode)
        return file
    except IOError:
        print("Error: Unable to open file.")
        return None


if __name__ == '__main__':
    process_session('D:\\temp\\SESSION-20240601_150303')
