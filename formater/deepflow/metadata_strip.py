import json


def extract_data(item):
    if isinstance(item, dict):
        if "__value__" in item:
            return extract_data(item["__value__"])
        else:
            return {k: extract_data(v) for k, v in item.items() if k not in ["__id__", "__type__"]}

    if isinstance(item, list):
        return [extract_data(i) for i in item]

    return item


def extract_metadata(item):
    if isinstance(item, dict):
        new_dict = {k: v for k, v in item.items() if k in ["__id__", "__type__"]}
        for k, v in item.items():
            if isinstance(v, (dict, list)) and k not in ["__id__", "__type__"]:
                new_dict[k] = extract_metadata(v)
        return new_dict

    if isinstance(item, list):
        return [extract_metadata(i) for i in item]
    return item


# Your original JSON structure
json_structure = {
    "__id__": "sfdS65",
    "__type__": "com.example.Report",
    "town": {
        "__id__": "AS45D",
        "__type__": "com.example.TownClass",
        "city-name": {
            "__id__": "826226",
            "__type__": "java.lang.String",
            "__value__": "New York"
        },
        "city-major": {
            "__id__": "826226",
            "__type__": "com.example.ChiefClass",
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
        },
        "citizens": {
            "__id__": "826226",
            "__type__": "com.example.ArrayList",
            "__value__": [
                {
                    "__id__": "fdssd6AS4",
                    "__type__": "com.example.Person",
                    "name": {
                        "__id__": "826226CT1",
                        "__type__": "java.lang.String",
                        "__value__": "citizen-1"
                    },
                    "children": {
                        "__id__": "826226",
                        "__type__": "com.example.ArrayList",
                        "__value__": [
                            {
                                "__id__": "LADS3DA5",
                                "__type__": "com.example.Person",
                                "name": {
                                    "__id__": "826226CT2",
                                    "__type__": "java.lang.String",
                                    "__value__": "child1"
                                }
                            }
                        ]
                    }
                },
                {
                    "__id__": "534fsa",
                    "__type__": "com.example.Person",
                    "name": {
                        "__id__": "826226CT2",
                        "__type__": "java.lang.String",
                        "__value__": "citizen-2"
                    }
                }
            ]
        }
    }
}

if __name__ == '__main__':
    json_string = '{"town":{"__type__":"java.lang.String","__id__":1320388319,"__value__":"London"},"__type__":"com.github.gabert.deepflow.demo.Main$Address","__id__":768185844}'

    json_data = json.loads(json_string)

    # data = extract_data(json_structure)
    # metadata = extract_metadata(json_structure)
    data = extract_data(json_data)
    metadata = extract_metadata(json_data)

    # Print results
    # print("Original JSON:")
    # print(json.dumps(json_structure, indent=4))

    print("Data JSON:")
    print(json.dumps(data, indent=4))

    print("\nMetadata JSON:")
    print(json.dumps(metadata, indent=4))
