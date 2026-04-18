"""
工具函数
"""


def get_status_desc(status_code: int, status_type: str = 'series') -> str:
    """获取状态描述"""
    status_map = {
        'series': {
            0: '初始化中',
            1: '待审核',
            2: '已锁定',
        },
        'role': {
            0: '提取中',
            1: '待审核',
            2: '已确认',
            3: '已锁定',
        },
        'asset': {
            0: '生成中',
            1: '待审核',
            2: '已确认',
            3: '已锁定',
        }
    }
    return status_map.get(status_type, {}).get(status_code, '未知')


def get_view_type_desc(view_type: str) -> str:
    """获取视图类型描述"""
    view_map = {
        'FRONT': '正面',
        'SIDE': '侧面',
        'BACK': '背面',
        'THREE_QUARTER': '四分之三',
    }
    return view_map.get(view_type, view_type)
