"""Small bounded reader for authoritative vanilla RCON data-get evidence (not used by production)."""
import json
import re


def parse(text):
    i = 0
    nodes = 0
    def space():
        nonlocal i
        while i < len(text) and text[i].isspace(): i += 1
    def quoted():
        nonlocal i
        delimiter = text[i]; i += 1; out = ''
        while i < len(text):
            c = text[i]; i += 1
            if c == delimiter: return out
            if c == '\\':
                if i >= len(text): raise ValueError('Unterminated SNBT escape')
                c = text[i]; i += 1
            out += c
        raise ValueError('Unterminated SNBT string')
    def value(depth=0):
        nonlocal i, nodes
        space(); nodes += 1
        if depth > 32 or nodes > 8192 or i >= len(text): raise ValueError('Unbounded SNBT evidence')
        c = text[i]
        if c in '\"\'': return quoted()
        if c == '{':
            i += 1; result = {}; space()
            while text[i] != '}':
                space()
                if text[i] in '\"\'': key = quoted()
                else:
                    start = i
                    while i < len(text) and text[i] not in ': ': i += 1
                    key = text[start:i]
                space()
                if text[i] != ':': raise ValueError('SNBT compound delimiter')
                i += 1; result[key] = value(depth+1); space()
                if text[i] == '}': break
                if text[i] != ',': raise ValueError('SNBT compound separator')
                i += 1
            i += 1; return result
        if c == '[':
            i += 1; space()
            if i+1 < len(text) and text[i+1] == ';': i += 2
            result = []; space()
            while text[i] != ']':
                result.append(value(depth+1)); space()
                if text[i] == ']': break
                if text[i] != ',': raise ValueError('SNBT list separator')
                i += 1
            i += 1; return result
        start = i
        while i < len(text) and text[i] not in ',]}': i += 1
        token = text[start:i].strip()
        if re.fullmatch(r'[+-]?\d+[bBsSlL]?', token): return int(token.rstrip('bBsSlL'))
        if re.fullmatch(r'[+-]?[\d.]+(?:[Ee][+-]?\d+)?[fFdD]?', token): return float(token.rstrip('fFdD'))
        return token
    result = value(); space()
    if i != len(text): raise ValueError('Trailing SNBT evidence')
    return result


def items(reply):
    marker = 'following entity data: '
    if marker not in reply: marker = 'following block data: '
    if marker not in reply: raise ValueError('Server returned no authoritative data: ' + reply)
    return parse(reply.split(marker, 1)[1])


def totals(stacks):
    result = {}
    def add(stack, copies=1):
        components = dict(stack.get('components', {}))
        nested = components.pop('minecraft:container', [])
        bundle = components.pop('minecraft:bundle_contents', [])
        # Tool wear while breaking is expected. Packed item metadata remains exact.
        if stack['id'].endswith('_pickaxe'): components.pop('minecraft:damage', None)
        key = stack['id'] + '|' + json.dumps(components, sort_keys=True, separators=(',', ':'))
        amount = copies * stack.get('count', 1); result[key] = result.get(key, 0) + amount
        for child in nested: add(child['item'], amount)
        for child in bundle: add(child, amount)
    for stack in stacks: add(stack)
    return result
