import json


def strip_and_separate_metadata(data):
    if isinstance(data, dict):
        if '__id__' in data and '__type__' in data and '__value__' in data:
            stripped = data['__value__']
            meta = {key: val for key, val in data.items() if key != '__value__'}

            if isinstance(stripped, dict):
                stripped, meta['__value__'] = strip_and_separate_metadata(stripped)
            elif isinstance(stripped, list):
                stripped_list = []
                meta_list = []
                for item in stripped:
                    new_item, new_meta = strip_and_separate_metadata(item)
                    stripped_list.append(new_item)
                    meta_list.append(new_meta)
                stripped = stripped_list
                meta['__value__'] = meta_list

            return stripped, meta
        else:
            new_stripped = {}
            new_meta = {}
            for key, val in data.items():
                new_stripped[key], new_meta[key] = strip_and_separate_metadata(val)
            return new_stripped, new_meta
    elif isinstance(data, list):
        new_data = []
        new_metadata = []
        for value in data:
            data_item, metadata_item = strip_and_separate_metadata(value)
            new_data.append(data_item)
            new_metadata.append(metadata_item)
        return new_data, new_metadata
    else:
        return data, {}


# Your original JSON structure
json_structure = {
    "town": {
        "__id__": "AS45D",
        "__type__": "com.example.TownClass",
        "__value__": {
            "name": {
                "__id__": "826226",
                "__type__": "java.lang.String",
                "__value__": "New York"
            },
            "chief": {
                "__id__": "826226",
                "__type__": "com.example.ChiefClass",
                "__value__": {
                        "name": {
                            "__id__": "826226N",
                            "__type__": "java.lang.String",
                            "__value__": "Arnold"
                        },
                    "surname": {
                        "__id__": "826226S",
                        "__type__": "java.lang.String",
                        "__value__": "Schwarzeneger"
                    }

                }
            },

            "citizens": {
                "__id__": "826226",
                "__type__": "com.example.ArrayList",
                "__value__": [
                    {"name": {
                        "__id__": "826226CT1",
                        "__type__": "java.lang.String",
                        "__value__": "citizen-1"
                        },
                     "children": {
                         "__id__": "826226",
                         "__type__": "com.example.ArrayList",
                         "__value__": [
                             {"name": {
                                 "__id__": "826226CT2",
                                 "__type__": "java.lang.String",
                                 "__value__": "child1"
                             }}
                         ]
                     }
                    },
                    {"name": {
                        "__id__": "826226CT2",
                        "__type__": "java.lang.String",
                        "__value__": "citizen-2"
                    }}
                ]
            }
        }
    }
}


if __name__ == '__main__':
    json_string = '{"__type__":"java.util.ImmutableCollections$List12","__id__":1033917063,"__array__":[{"name":{"__type__":"java.lang.String","__id__":230526532,"__value__":"John"},"address":{"town":{"__type__":"java.lang.String","__id__":584561912,"__value__":"London"},"__type__":"com.github.gabert.deepflow.demo.Main$Address","__id__":1937380187},"__type__":"com.github.gabert.deepflow.demo.Main$Person","__id__":366803687}]}'

    json_data = json.loads(json_string)


    data, metadata = strip_and_separate_metadata(json_data)

    # Print results
    print("Original JSON:")
    print(json.dumps(json_data, indent=4))

    print("Data JSON:")
    print(json.dumps(data, indent=4))

    print("\nMetadata JSON:")
    print(json.dumps(metadata, indent=4))

