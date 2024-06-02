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
            "MS": lambda record: self.__format_ms(record),
            "TS": lambda record: self.__format_ts_te(record),
            "TE": lambda record: self.__format_ts_te(record),
            "AR": lambda record: self.__format_ar_re(record),
            "RE": lambda record: self.__format_ar_re(record)
        }

    def format(self, record):
        yaml_lines = self.record_formats.get(record["type"], lambda _: [])(record)
        self.previous_level = record["depth"]
        return yaml_lines

    def __format_ms(self, record):
        data = record['value']

        indent_size = self.__calculate_indent(record['type'], record['depth'])
        indent_string = ' ' * indent_size
        method_indent_string = (' ' * self.indent).replace(' ', '-', 1)

        yaml_lines = []

        if record['depth'] > self.previous_level:
            yaml_lines.append(indent_string + 'CS:')

        yaml_lines.append(f"{indent_string}{method_indent_string}'{data}':")

        return yaml_lines

    def __format_ts_te(self, record):
        data = record['value']
        offset_string = ' ' * self.__calculate_indent(record['type'], record['depth'])
        yaml_lines = [f"{offset_string}{record['type']}: '{data}'"]
        return yaml_lines

    def __format_ar_re(self, record):
        if record['type'] == "AR":
            identity_tag = 'AI'
        elif record['type'] == "RE":
            identity_tag = 'RI'
        else:
            raise ValueError(f"Record type {record['type']} not supported")

        raw_data_yaml_lines = self.__convert_to_yaml(record['raw_data'],
                                                     record['type'],
                                                     record['type'],
                                                     record['depth'])
        meta_data_yaml_lines = self.__convert_to_yaml(record['meta_data'],
                                                      identity_tag,
                                                      record['type'],
                                                      record['depth'])

        yaml_lines = raw_data_yaml_lines + meta_data_yaml_lines

        return yaml_lines

    def __convert_to_yaml(self, data, tag, record_type, depth):
        tagged_object = {tag: data}
        yaml_string = yaml.dump(tagged_object, indent=self.indent, sort_keys=True).rstrip('\n')

        offset_string = ' ' * self.__calculate_indent(record_type, depth)
        yaml_lines = []
        for yaml_line in yaml_string.split("\n"):
            yaml_lines.append(f'{offset_string}{yaml_line}')
        return yaml_lines

    def __calculate_indent(self, record_type, depth):
        if record_type == 'MS':
            return depth * (2 * self.indent)
        else:
            return (depth + 1) * (2 * self.indent)


class FormaterFactory:
    __formatters = {
        'yaml': YamlLineFormater
    }

    @staticmethod
    def get_formater(destination_format):
        formater_class = FormaterFactory.__formatters.get(destination_format.lower())
        if formater_class is None:
            raise ValueError(f"Unknown format: {destination_format}")
        return formater_class()
