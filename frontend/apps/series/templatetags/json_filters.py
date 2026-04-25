import json
from django import template
from django.utils.safestring import mark_safe

register = template.Library()


@register.filter(name='to_json')
def to_json(value):
    """
    Convert a Python object to JSON string for use in HTML data attributes.
    Properly escapes quotes and special characters.
    """
    if value is None:
        return '[]'
    try:
        return mark_safe(json.dumps(value, ensure_ascii=False))
    except (TypeError, ValueError):
        return '[]'


@register.filter(name='modulo')
def modulo(value, arg):
    """
    Return value % arg
    """
    try:
        return int(value) % int(arg)
    except (ValueError, TypeError):
        return 0


@register.filter(name='div')
def div(value, arg):
    """
    Return value // arg (integer division)
    """
    try:
        return int(value) // int(arg)
    except (ValueError, TypeError):
        return 0
