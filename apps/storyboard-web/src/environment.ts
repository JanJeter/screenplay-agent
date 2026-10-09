export const javaApiBase = import.meta.env.VITE_JAVA_API_BASE ?? ''
export const currentWorkbenchUrl = 'http://127.0.0.1:5174/'

function usesArchivedJava(base: string): boolean {
  try {
    const url = new URL(base || window.location.origin, window.location.origin)
    const hostname = url.hostname.replace(/^\[|\]$/g, '').toLowerCase()
    return ['localhost', '127.0.0.1', '::1'].includes(hostname) && url.port === '18083'
  } catch { return false }
}

export const isLegacyWorkbench = usesArchivedJava(javaApiBase)
export const legacyReadOnlyMessage = '这是封存的历史测试环境，只能查看。请点击“打开新工作台”，继续创建、生成或编辑。'
