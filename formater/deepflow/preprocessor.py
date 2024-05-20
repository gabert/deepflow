import json
import hashlib
import copy
from collections import OrderedDict


def compute_hash(obj):
    if isinstance(obj, dict):
        for key, value in obj.items():
            obj[key] = compute_hash(value)

        obj = OrderedDict(sorted(obj.items()))

        hash_object = hashlib.md5(json.dumps(obj, sort_keys=True).encode())
        obj['__hash__'] = hash_object.hexdigest()

    elif isinstance(obj, list):
        obj = [compute_hash(item) for item in obj]

    return obj


def extract_data(obj):
    if isinstance(obj, dict):
        for key in list(obj.keys()):
            if key == "__meta_id__" or key == "__meta_type__":  # Remove meta keys
                del obj[key]
            else:  # Process nested dictionaries
                obj[key] = extract_data(obj[key])
        return obj
    elif isinstance(obj, list):  # Process list items
        return [extract_data(item) for item in obj]
    else:
        return obj


def extract_meta(obj):
    if isinstance(obj, dict):
        for key in list(obj.keys()):
            if key != "__meta_id__" and key != "__meta_type__" and not isinstance(obj[key], (dict, list)):
                del obj[key]
            else:
                obj[key] = extract_meta(obj[key])
        return obj
    elif isinstance(obj, list):  # Process list items
        return [extract_meta(item) for item in obj]
    else:
        return obj


def merge_json(hash_data, hash_meta):
    if isinstance(hash_data, dict):
        for key in hash_data.keys():
            if isinstance(hash_data[key], (dict, list)):
                hash_data[key] = merge_json(hash_data[key], hash_meta[key])
            else:
                if key == '__hash__':
                    hash_data[
                        "__meta_id__"] = f"ID:{hash_meta['__meta_id__']}-{hash_data['__hash__']}-{hash_meta['__hash__']}"
                    hash_data["__meta_type__"] = hash_meta['__meta_type__']
                    del hash_data["__hash__"]
    elif isinstance(hash_data, list):
        for i in range(len(hash_data)):
            hash_data[i] = merge_json(hash_data[i], hash_meta[i])

    return hash_data


def hash_update(json_str):
    json_input = json.loads(json_str)
    json_data = extract_data(copy.deepcopy(json_input))
    json_meta = extract_meta(copy.deepcopy(json_input))
    json_hash_data = compute_hash(copy.deepcopy(json_data))
    json_hash_meta = compute_hash(copy.deepcopy(json_meta))
    json_data_merged = merge_json(copy.deepcopy(json_hash_data), copy.deepcopy(json_hash_meta))

    return json_data_merged
