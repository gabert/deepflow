import unittest

from deepflow.preprocessor import compute_hash, hash_update

json_test_string = '''
{
      "name": "John",
      "privateAddress": {
          "townName": "London",
          "__meta_id__": 1876631416,
          "__meta_type__": "com.github.gabert.deepflow.serializer.Main$Address"
      },
      "businessAddresses": {
          "__meta_id__": 1225616405,
          "__meta_type__": "java.util.ArrayList",
          "__meta_array__": [
              {
                  "townName": "Paris",
                  "__meta_id__": 184966243,
                  "__meta_type__": "com.github.gabert.deepflow.serializer.Main$Address"
              },
              {
                  "townName": "New York",
                  "__meta_id__": 124313277,
                  "__meta_type__": "com.github.gabert.deepflow.serializer.Main$Address"
              }
          ]
      },
      "__meta_id__": 2101842856,
      "__meta_type__": "com.github.gabert.deepflow.serializer.Main$Person"
  }
'''


class TestComputeHash(unittest.TestCase):
    def test_compute_hash_dictionary(self):
        test_dict = {'a': 1,
                     'b': {'c': 2},
                     'd': {
                         '__meta_array__':
                             [1,
                              {'x': 11,
                               'y': 12},
                              3]
                     }}
        result = compute_hash(test_dict)
        self.assertIn('__hash__', result)
        self.assertIn('__hash__', result['b'])
        self.assertIn('__hash__', result['d'])
        self.assertIn('__hash__', result['d']['__meta_array__'][1])

        self.assertNotEqual(result['__hash__'],
                            result['b']['__hash__'])
        self.assertNotEqual(result['b']['__hash__'],
                            result['d']['__hash__'])
        self.assertNotEqual(result['d']['__hash__'],
                            result['d']['__meta_array__'][1]['__hash__'])


class TestComputeJsonHashUpdate(unittest.TestCase):
    def test_compute_json_hash_update(self):
        result = hash_update(json_test_string)

        self.assertIn('__meta_id__', result)
        self.assertIn('__meta_id__', result['privateAddress'])
        self.assertIn('__meta_id__', result['businessAddresses'])
        self.assertIn('__meta_id__', result['businessAddresses']['__meta_array__'][0])
        self.assertIn('__meta_id__', result['businessAddresses']['__meta_array__'][1])

        self.assertNotIn('__hash__', result)
        self.assertNotIn('__hash__', result['privateAddress'])
        self.assertNotIn('__hash__', result['businessAddresses'])
        self.assertNotIn('__hash__', result['businessAddresses']['__meta_array__'][0])
        self.assertNotIn('__hash__', result['businessAddresses']['__meta_array__'][1])

        self.assertEqual(2, len(result['__meta_id__'].split(":")))
        self.assertEqual(3, len(result['__meta_id__'].split("-")))
