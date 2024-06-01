from abc import ABC, abstractmethod

import yaml


class BaseFormater(ABC):
    @abstractmethod
    def format(self, data):
        pass


class YamlLineFormater(BaseFormater):
    def __init__(self):
        self.indent = 4
        self.previous_level = -1
        self.record_formats = {
            "MS": lambda record: self.__format_ms(record, self.previous_level),
            # "TS": lambda record: format_ts(record, 'TS'),
            # "TE": lambda record: format_ts(record, 'TE'),
            # "AR": lambda record: format_ar(record),
            # "RE": lambda record: format_re(record)
        }

    def format(self, record):
        yaml_lines = self.record_formats.get(record["type"], lambda _: [])(record)
        return yaml_lines

    def __format_ms(self, record, previous_level):
        data = record['value']

        indent_size = self.__calculate_indent(record['type'], record['depth'])
        indent_string = ' ' * indent_size
        method_indent_string = (' ' * self.indent).replace(' ', '-', 1)

        yaml_lines = []

        if record['depth'] > previous_level:
            yaml_lines.append(indent_string + 'CS:')

        yaml_lines.append(f"{indent_string}{method_indent_string}'{data}':")

        return yaml_lines

    def __calculate_indent(self, record_type, depth):
        if record_type == 'MS':
            return depth * (2 * self.indent)
        else:
            return (depth + 1) * (2 * self.indent)
