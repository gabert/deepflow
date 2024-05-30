import json
import yaml
import os

from deepflow import preprocessor

delimiter = ";"
indent = 4


def process_session(directory):
    for filename in os.listdir(directory):
        if filename.endswith('.dmp'):
            dump_file_path = os.path.join(directory, filename)
            yaml_file_path = f'{filename}.yaml'
            process_dump_file(dump_file_path, yaml_file_path)


def process_dump_file(dump_file_path, yaml_file_path):
    yaml_file = open_file(yaml_file_path, 'w')
    dump_file = open_file(dump_file_path)

    previous_level = -1
    for dump_line in dump_file:
        yaml_entry, previous_level = process_dump_line(dump_line.rstrip('\n'), previous_level)
        dump_to_yaml(yaml_entry, yaml_file)

    dump_file.close()
    yaml_file.close()


def process_dump_line(line, previous_level):
    record_formats = {
        "MS": lambda record: format_ms(record, previous_level),
        "TS": lambda record: format_ts(record, 'TS'),
        "TE": lambda record: format_ts(record, 'TE'),
        "AR": lambda record: format_ar(record),
        "RE": lambda record: format_re(record)
    }

    fields = split_line(line)
    record_type = fields[2]
    record = parse_fields_ts(fields) if record_type in ["MS", "ME"] else parse_fields(fields)

    yaml_entries = record_formats.get(record["type"], lambda _: [])(record)

    return yaml_entries, record['depth']


def dump_to_yaml(yaml_entries, yaml_file):
    for entry in yaml_entries:
        yaml_file.write(f'{entry}\n')


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


def parse_fields_ts(fields):
    return {
        "depth": int(fields[0]),
        "thread": fields[1],
        "type": fields[2],
        "value": fields[3]
    }


def format_ms(record, previous_level):
    data = record['value']
    offset_string = ' ' * indent_size(record)
    yaml_lines = []

    if record['depth'] > previous_level:
        yaml_lines.append(offset_string + 'CS:')

    yaml_lines.append(f"{offset_string}{(' ' * indent).replace(' ', '-', 1)}'{data}':")
    return yaml_lines


def format_ts(record, tag):
    data = record['value']
    offset_string = ' ' * indent_size(record)
    yaml_lines = []
    yaml_lines.append(f"{offset_string}{tag}: '{data}'")
    return yaml_lines


def format_ar(record):
    yaml_data = json_to_yaml(record['value'], "AR")
    offset_string = ' ' * indent_size(record)
    yaml_lines = []
    for yaml_line in yaml_data.split("\n"):
        yaml_lines.append(f'{offset_string}{yaml_line}')
    return yaml_lines


def format_re(record):
    yaml_data = json_to_yaml(record['value'], "RE")
    offset_string = ' ' * indent_size(record)
    yaml_lines = []
    for yaml_line in yaml_data.split("\n"):
        yaml_lines.append(f'{offset_string}{yaml_line}')
    return yaml_lines


def json_to_yaml(json_string, tag):
    json_data = preprocessor.hash_update(json_string)
    sorted_data = sort_keys(json_data)
    tag_object = {tag: sorted_data}
    json_s = json.dumps(tag_object)
    return yaml.dump(json.loads(json_s), indent=indent, sort_keys=False).rstrip('\n')


def sort_keys(d):
    if not isinstance(d, dict):
        return d

    keys_order = ['__type__', '__id__', '__array__']
    other_keys = [k for k in d if k not in keys_order]
    sorted_keys = keys_order + other_keys
    return {k: sort_keys(d[k]) for k in sorted_keys}


def indent_size(record):
    if record['type'] == 'MS':
        return record['depth'] * (2 * indent)
    else:
        return (record['depth'] + 1) * (2 * indent)


def open_file(filename, mode='r'):
    try:
        file = open(filename, mode)
        return file
    except IOError:
        print("Error: Unable to open file.")
        return None


if __name__ == '__main__':
    process_session('D:\\temp\\SESSION-20240530_223454')
