import json
import hashlib
import copy
from collections import OrderedDict

json_string = '''
{
      "name": "John",
      "privateAddress": {
          "townName": "London",
          "__meta_id__": 1876631416,
          "__meta_type__": "com.github.gabert.deepflow.serializer.SerMain$Address"
      },
      "businessAddresses": {
          "__meta_id__": 1225616405,
          "__meta_type__": "java.util.ArrayList",
          "__meta_array__": [
              {
                  "townName": "Paris",
                  "__meta_id__": 184966243,
                  "__meta_type__": "com.github.gabert.deepflow.serializer.SerMain$Address"
              },
              {
                  "townName": "New York",
                  "__meta_id__": 124313277,
                  "__meta_type__": "com.github.gabert.deepflow.serializer.SerMain$Address"
              }
          ]
      },
      "__meta_id__": 2101842856,
      "__meta_type__": "com.github.gabert.deepflow.serializer.SerMain$Person"
  }
'''


def compute_hash(obj):
    sorted_obj = copy.deepcopy(obj)

    if isinstance(sorted_obj, dict):
        for key, value in sorted_obj.items():
            sorted_obj[key] = compute_hash(value)

        sorted_obj = OrderedDict(sorted(sorted_obj.items()))

        hash_object = hashlib.md5(json.dumps(sorted_obj, sort_keys=True).encode())
        sorted_obj['__hash__'] = hash_object.hexdigest()

    elif isinstance(sorted_obj, list):
        sorted_obj = [compute_hash(item) for item in sorted_obj]

    return sorted_obj


def extract_data(obj):
    if isinstance(obj, dict):
        copy_obj = copy.deepcopy(obj)
        for key in list(copy_obj.keys()):
            if key == "__meta_id__" or key == "__meta_type__":  # Remove meta keys
                del copy_obj[key]
            else:  # Process nested dictionaries
                copy_obj[key] = extract_data(copy_obj[key])
        return copy_obj
    elif isinstance(obj, list):  # Process list items
        return [extract_data(item) for item in obj]
    else:
        return obj


def extract_meta(obj):
    if isinstance(obj, dict):
        copy_obj = copy.deepcopy(obj)
        for key in list(copy_obj.keys()):
            if key != "__meta_id__" and key != "__meta_type__" and not isinstance(copy_obj[key], (dict, list)):
                del copy_obj[key]
            else:
                copy_obj[key] = extract_meta(copy_obj[key])
        return copy_obj
    elif isinstance(obj, list):  # Process list items
        return [extract_meta(item) for item in obj]
    else:
        return obj


def merge_json(hash_data, hash_meta):
    return _merge_internal(copy.deepcopy(hash_data), copy.deepcopy(hash_meta))


def _merge_internal(hash_data, hash_meta):
    if isinstance(hash_data, dict):
        for key in hash_data.keys():
            if isinstance(hash_data[key], (dict, list)):
                hash_data[key] = merge_json(hash_data[key], hash_meta[key])
            else:
                if key == '__hash__':
                    hash_data["__meta_id__"] = f"ID:{hash_meta['__meta_id__']}-{hash_data['__hash__']}-{hash_meta['__hash__']}"
                    hash_data["__meta_type__"] = hash_meta['__meta_type__']
                    del hash_data["__hash__"]
    elif isinstance(hash_data, list):
        for i in range(len(hash_data)):
            hash_data[i] = merge_json(hash_data[i], hash_meta[i])

    return hash_data


def hash_update(json_str):
    json_input = json.loads(json_string)
    json_data = extract_data(json_input)
    json_meta = extract_meta(json_input)
    json_hash_data = compute_hash(json_data)
    json_hash_meta = compute_hash(json_meta)
    json_data_merged = merge_json(json_hash_data, json_hash_meta)

    # print(json.dumps(json_data_input, indent=4, sort_keys=True))
    # print('--------------------')
    # print(json.dumps(json_data_raw, indent=4, sort_keys=True))
    # print('--------------------')
    # print(json.dumps(json_data_meta, indent=4, sort_keys=True))
    print('--------------------')
    print(json.dumps(json_hash_data, indent=4, sort_keys=True))
    print('--------------------')
    print(json.dumps(json_hash_meta, indent=4, sort_keys=True))
    print('--------------------')
    print(json.dumps(json_data_merged, indent=4, sort_keys=True))


if __name__ == '__main__':
    hash_update(json_string)

    # print(extract_data(json_string))
    # print(extract_metadata(json_string))

    # data = json.loads(json_string)
    # data2 = json.loads(json_string_2)
    # data3 = json.loads(json_string_3)
    # data4 = json.loads(json_string_4)
    # output_meta = extract_meta(data)
    # output_data = extract_data(data)
    # output_data_2 = extract_data(data2)
    # output_data_3 = extract_data(data3)
    # output_data_4 = extract_data(data4)

    # print(json.dumps(data, indent=4, sort_keys=True))
    # print("1. ------------")
    # print(json.dumps(output_meta, indent=4, sort_keys=True))
    # print("2. ------------")
    # print(json.dumps(output_data, indent=4, sort_keys=True))
    # print("3. ------------")
    # print(json.dumps(compute_hash(output_data), indent=4, sort_keys=True))
    # print("4. ------------")
    # print(json.dumps(compute_hash(output_data_2), indent=4, sort_keys=True))
    # print("5. ------------")
    # print(json.dumps(compute_hash(output_data_3), indent=4, sort_keys=True))
    # print("6. ------------")
    # print(json.dumps(compute_hash(output_data_4), indent=4, sort_keys=True))
