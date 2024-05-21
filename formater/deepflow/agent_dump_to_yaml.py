import json
import yaml

delimiter = ";"
indent = 4


def process_dump(dump, yaml_dump):
    yaml_file = open_file(yaml_dump, 'w')
    dump_file = open_file(dump)

    previous_level = -1
    for line in dump_file:
        yaml_entry, previous_level = process_line(line.rstrip('\n'), previous_level)
        dump_to_yaml(yaml_entry, yaml_file)

    dump_file.close()
    yaml_file.close()


def dump_to_yaml(yaml_entries, yaml_file):
    for entry in yaml_entries:
        yaml_file.write(f'{entry}\n')


def process_line(line, previous_level):
    record_formats = {
        "MS": lambda record: format_ms(record, previous_level),
        "AR": lambda record: format_element(record, "AR"),
        "RE": lambda record: format_element(record, "RE")
    }

    fields = line.split(delimiter)
    record_type = fields[2]
    record = parse_fields_ts(fields) if record_type in ["MS", "ME"] else parse_fields(fields)

    yaml_entries = record_formats.get(record["type"], lambda _: [])(record)

    return yaml_entries, record['level']


def parse_fields(fields):
    return {
        "level": int(fields[0]),
        "thread": fields[1],
        "type": fields[2],
        "value": fields[3]
    }


def parse_fields_ts(fields):
    return {
        "level": int(fields[0]),
        "thread": fields[1],
        "type": fields[2],
        "time_stamp": fields[3],
        "value": fields[5]
    }


def format_ms(record, previous_level):
    data = record['value']
    offset_string = ' ' * indent_size(record)
    yaml_lines = []
    if record['level'] > previous_level:
        yaml_lines.append(offset_string + 'CS:')
    yaml_lines.append(f'{offset_string}{(" " * indent).replace(" ", "-", 1)}{data}:')
    return yaml_lines


def format_element(record, tag):
    yaml_data = json_to_yaml(record['value'])
    offset_string = ' ' * indent_size(record)
    yaml_lines = [f'{offset_string}{tag}:']
    for yaml_line in yaml_data.split("\n"):
        if yaml_line[-2:] == '[]':
            yaml_lines[-1] += ' []'
            continue
        yaml_lines.append(f'{offset_string}{yaml_line}')
    return yaml_lines


def json_to_yaml(json_string):
    json_data = json.loads(json_string)
    return yaml.dump(json_data, indent=indent).rstrip('\n')


def indent_size(record):
    if record['type'] == 'MS':
        return record['level'] * (2 * indent)
    else:
        return (record['level'] + 1) * (2 * indent)


def open_file(filename, mode='r'):
    try:
        file = open(filename, mode)
        return file
    except IOError:
        print("Error: Unable to open file.")
        return None


if __name__ == '__main__':
    process_dump('C:\\temp\\agent_log.dmp',
                 'C:\\temp\\agent_dump.yaml')
