import json
import hashlib
import copy


def compute_hash(obj):
    if isinstance(obj, dict):
        for key, value in obj.items():
            obj[key] = compute_hash(value)

        hash_object = hashlib.md5(json.dumps(obj, sort_keys=True).encode())
        obj['__hash__'] = hash_object.hexdigest()

    elif isinstance(obj, list):
        obj = [compute_hash(item) for item in obj]

    return obj


def extract_data(obj):
    if isinstance(obj, dict):
        return {k: extract_data(v) for k, v in obj.items()
                if k not in ("object_id", "class")}
    elif isinstance(obj, list):
        return [extract_data(item) for item in obj]
    else:
        return obj


def extract_meta(obj):
    if isinstance(obj, dict):
        result = {k: v for k, v in obj.items() if k in ("object_id", "class")}
        for k, v in obj.items():
            if isinstance(v, (dict, list)) and k not in ("object_id", "class"):
                result[k] = extract_meta(v)
        return result
    elif isinstance(obj, list):
        return [extract_meta(item) for item in obj]
    else:
        return obj


def merge_json(hash_data, hash_meta):
    if isinstance(hash_data, dict):
        if '__hash__' in hash_data and 'object_id' not in hash_meta:
            del hash_data['__hash__']
            return hash_data
        for key in list(hash_data.keys()):
            if key == '__hash__':
                hash_data["object_id"] = f"ID:{hash_meta['object_id']}-CH:{hash_data['__hash__']}-IH:{hash_meta['__hash__']}"
                hash_data["class"] = hash_meta['class']
                del hash_data["__hash__"]
            elif isinstance(hash_data[key], (dict, list)):
                hash_data[key] = merge_json(hash_data[key], hash_meta.get(key, hash_data[key]))
    elif isinstance(hash_data, list):
        for i in range(len(hash_data)):
            if i < len(hash_meta) and isinstance(hash_data[i], dict):
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
