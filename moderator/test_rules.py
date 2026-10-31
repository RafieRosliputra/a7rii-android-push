import unittest
from rules import decide, SAFE, RISKY, PROMPTS


def clip(**weights):
    """Spread probabilities: weights per group name ('safe' or a RISKY key)."""
    total = sum(weights.values())
    out = {p: 0.0 for p in PROMPTS}
    for name, w in weights.items():
        prompts = SAFE if name == "safe" else RISKY[name]
        for p in prompts:
            out[p] = w / total / len(prompts)
    return out


class Rules(unittest.TestCase):
    def test_selfie_ok(self):
        self.assertEqual(decide(0.02, clip(safe=9, politics=0.5, document=0.5), 0.01), ("ok", []))

    def test_nsfw_block_and_unsure_flag(self):
        self.assertEqual(decide(0.93, clip(safe=1), 0)[0], "block")
        self.assertEqual(decide(0.6, clip(safe=1), 0), ("flag", ["nsfw?"]))

    def test_politics_and_document(self):
        self.assertIn("politics", decide(0.01, clip(safe=4, politics=6), 0)[1])
        v, labels = decide(0.01, clip(safe=3, document=7), 0.4)
        self.assertEqual(v, "flag")
        self.assertIn("document", labels)
        self.assertIn("text", labels)

    def test_text_alone(self):
        self.assertEqual(decide(0.0, clip(safe=1), 0.2), ("flag", ["text"]))

    def test_small_risky_share_ok(self):
        self.assertEqual(decide(0.0, clip(safe=8, violence=1, **{"no people": 1}), 0.05)[0], "ok")


if __name__ == "__main__":
    unittest.main()
