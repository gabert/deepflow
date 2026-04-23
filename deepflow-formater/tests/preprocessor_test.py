import unittest

from deepflow.hasher import compute_hash, hash_update

json_test_string = '''
{
      "object_id": 2101842856,
      "class": "com.github.gabert.deepflow.serializer.Main$Person",
      "name": "John",
      "privateAddress": {
          "object_id": 1876631416,
          "class": "com.github.gabert.deepflow.serializer.Main$Address",
          "townName": "London"
      },
      "businessAddresses": {
          "object_id": 1225616405,
          "class": "java.util.ArrayList",
          "items": [
              {
                  "object_id": 184966243,
                  "class": "com.github.gabert.deepflow.serializer.Main$Address",
                  "townName": "Paris"
              },
              {
                  "object_id": 124313277,
                  "class": "com.github.gabert.deepflow.serializer.Main$Address",
                  "townName": "New York"
              }
          ]
      }
  }
'''


class TestComputeHash(unittest.TestCase):
    def test_compute_hash_dictionary(self):
        test_dict = {'a': 1,
                     'b': {'c': 2},
                     'd': [1,
                           {'x': 11,
                            'y': 12},
                           3]}
        result = compute_hash(test_dict)
        self.assertIn('__hash__', result)
        self.assertIn('__hash__', result['b'])
        self.assertIn('__hash__', result['d'][1])

        self.assertNotEqual(result['__hash__'],
                            result['b']['__hash__'])
        self.assertNotEqual(result['b']['__hash__'],
                            result['d'][1]['__hash__'])


class TestComputeJsonHashUpdate(unittest.TestCase):
    def test_compute_json_hash_update(self):
        result = hash_update(json_test_string)

        self.assertIn('object_id', result)
        self.assertIn('object_id', result['privateAddress'])
        self.assertIn('object_id', result['businessAddresses'])

        items = result['businessAddresses']['items']
        self.assertIn('object_id', items[0])
        self.assertIn('object_id', items[1])

        self.assertNotIn('__hash__', result)
        self.assertNotIn('__hash__', result['privateAddress'])
        self.assertNotIn('__hash__', items[0])
        self.assertNotIn('__hash__', items[1])

        self.assertIn('ID:', result['object_id'])
        self.assertIn('-CH:', result['object_id'])
        self.assertIn('-IH:', result['object_id'])
