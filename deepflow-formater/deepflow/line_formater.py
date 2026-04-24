class LlmLineFormater:
    def format(self, record):
        entries = []

        if record['type'] == 'MS':
            entries.append(f"{record['method_id']};{record['depth']};{record['type']};{record['value']}")
            entries.append(f"{record['method_id']};{record['depth']};PM;{record['parent_method_id']}")
        else:
            entries.append(f"{record['method_id']};{record['depth']};{record['type']};{record['value']}")

        return entries


class FormaterFactory:
    __formatters = {
        'llm': LlmLineFormater
    }

    @staticmethod
    def get_formater(destination_format):
        formater_class = FormaterFactory.__formatters.get(destination_format.lower())
        if formater_class is None:
            raise ValueError(f"Unknown format: {destination_format}")
        return formater_class()
