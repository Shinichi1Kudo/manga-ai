from django import forms


class SeriesInitForm(forms.Form):
    """系列初始化表单"""
    series_name = forms.CharField(
        label='系列名称',
        max_length=100,
        widget=forms.TextInput(attrs={'class': 'form-control', 'placeholder': '请输入系列名称'})
    )
    outline = forms.CharField(
        label='剧本大纲',
        widget=forms.Textarea(attrs={'class': 'form-control', 'rows': 5, 'placeholder': '请输入剧本大纲'})
    )
    background = forms.CharField(
        label='背景设定',
        required=False,
        widget=forms.Textarea(attrs={'class': 'form-control', 'rows': 3, 'placeholder': '请输入背景设定/世界观'})
    )
    character_intro = forms.CharField(
        label='人物介绍',
        widget=forms.Textarea(attrs={'class': 'form-control', 'rows': 10, 'placeholder': '请输入人物介绍，每个角色用空行分隔'})
    )
    style_keywords = forms.CharField(
        label='风格关键词',
        required=False,
        max_length=500,
        widget=forms.TextInput(attrs={'class': 'form-control', 'placeholder': '如：日系、少年漫、热血'})
    )
    color_preference = forms.CharField(
        label='色调偏好',
        required=False,
        max_length=200,
        widget=forms.TextInput(attrs={'class': 'form-control', 'placeholder': '如：明亮、暖色调'})
    )
