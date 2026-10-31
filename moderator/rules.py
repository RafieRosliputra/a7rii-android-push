"""Decision rules for guest photos (pure functions, no models: easy to test and to tune).

Inputs come from three checks run by app.py:
  nsfw   probability the photo is sexual / nude (NSFW image classifier)
  clip   probabilities over the CLIP prompts below (they sum to 1)
  text   share of the photo covered by text (OCR boxes), 0..1
Output: verdict 'ok' | 'flag' | 'block' and the labels that caused it.
"""

# What a fun guest photo at an event looks like. Anything else competes against these.
SAFE = [
    "a selfie of a person smiling",
    "a group photo of friends at an event",
    "people at a wedding, dinner or party",
    "a photo of a stage performance or speaker",
    "children at a school event",
    "food and drinks on a table at an event",
    "event decorations, flowers and lights",
]
RISKY = {
    "politics": [
        "a political party flag or logo",
        "an election campaign poster",
        "a political protest or demonstration with banners",
    ],
    "document": [
        "a document, letter or printed page",
        "a screenshot of a phone chat or social media post",
        "a photo of a computer screen with text",
    ],
    "violence": [
        "a gun, knife or weapon",
        "blood or a bloody injury",
        "people fighting violently",
    ],
    "no people": [
        "a meme or cartoon picture",
        "an empty floor, wall or random object with no people",
    ],
    "rude gesture": [
        "a person showing the middle finger",
        "an obscene or sexual hand gesture",
    ],
}
PROMPTS = SAFE + [p for group in RISKY.values() for p in group]

# "Sederhana" (Rafi's choice): unsure goes to a person.
NSFW_BLOCK = 0.85
NSFW_FLAG = 0.50
GROUP_FLAG = 0.35       # a risky group holds this share of the CLIP probability
GROUP_TOP_FLAG = 0.25   # ...or it is the single largest group and above this
TEXT_FLAG = 0.15        # Rafi: flag when text covers more than ~15% of the photo


def decide(nsfw: float, clip: dict, text: float):
    labels = []
    if nsfw >= NSFW_BLOCK:
        return "block", ["nsfw"]
    if nsfw >= NSFW_FLAG:
        labels.append("nsfw?")
    groups = {name: sum(clip.get(p, 0.0) for p in prompts) for name, prompts in RISKY.items()}
    safe = sum(clip.get(p, 0.0) for p in SAFE)
    top = max(groups, key=groups.get) if groups else None
    for name, share in groups.items():
        if share >= GROUP_FLAG or (name == top and share >= GROUP_TOP_FLAG and share > safe):
            labels.append(name)
    if text >= TEXT_FLAG:
        labels.append("text")
    return ("flag" if labels else "ok"), labels
