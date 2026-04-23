import json


def extract_data(item):
    if isinstance(item, dict):
        return {k: extract_data(v) for k, v in item.items()
                if k not in ("object_id", "class")}

    if isinstance(item, list):
        return [extract_data(i) for i in item]

    return item


def extract_metadata(item):
    if isinstance(item, dict):
        result = {k: v for k, v in item.items() if k in ("object_id", "class")}
        for k, v in item.items():
            if isinstance(v, (dict, list)) and k not in ("object_id", "class"):
                result[k] = extract_metadata(v)
        return result

    if isinstance(item, list):
        return [extract_metadata(i) for i in item]
    return item
