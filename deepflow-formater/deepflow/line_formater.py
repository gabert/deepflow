class LlmLineFormater:
    def format(self, record):
        entries = []

        if record['type'] == 'MS':
            entries.append(f"{record['method_id']};{record['depth']};{record['type']};{record['value']}")
            entries.append(f"{record['method_id']};{record['depth']};PM;{record['parent_method_id']}")
        elif record['type'] == 'AR':
            entries.append(f"{record['method_id']};{record['depth']};AR;{record['raw_data']}")
            entries.append(f"{record['method_id']};{record['depth']};AI;{record['meta_data']}")
        elif record['type'] == 'RE':
            entries.append(f"{record['method_id']};{record['depth']};RE;{record['raw_data']}")
            entries.append(f"{record['method_id']};{record['depth']};RI;{record['meta_data']}")
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
