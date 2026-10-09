import { Navigate, RouterProvider, createBrowserRouter, useParams } from 'react-router-dom'
import { ApiProvider } from './api/api-context'
import { AuthProvider } from './auth'
import { LoginPage } from './pages/LoginPage'
import { ProjectsPage } from './pages/ProjectsPage'
import { ScriptsPage } from './pages/ScriptsPage'
import { StoryboardWorkspacePage } from './pages/StoryboardWorkspacePage'
import { EmailRequestPage, RegisterPage, ResetPasswordPage, VerifyEmailPage } from './pages/AccountPages'
import { AdminLayout, AdminRolesPage, AdminRunsPage, AdminUsagePage, AdminUsersPage } from './pages/AdminPages'

function StoryboardRoute() {
  const { projectId = '', scriptId = '' } = useParams()
  return <StoryboardWorkspacePage key={`${projectId}/${scriptId}`} />
}

const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  { path: '/register', element: <RegisterPage /> },
  { path: '/verify-email', element: <VerifyEmailPage /> },
  { path: '/resend-verification', element: <EmailRequestPage kind="resend-verification" /> },
  { path: '/forgot-password', element: <EmailRequestPage kind="forgot-password" /> },
  { path: '/reset-password', element: <ResetPasswordPage /> },
  { path: '/admin', element: <AdminLayout />, children: [
    { index: true, element: <Navigate to="/admin/users" replace /> },
    { path: 'users', element: <AdminUsersPage /> },
    { path: 'roles', element: <AdminRolesPage /> },
    { path: 'runs', element: <AdminRunsPage /> },
    { path: 'usage', element: <AdminUsagePage /> }
  ] },
  { path: '/projects', element: <ProjectsPage /> },
  { path: '/projects/:projectId/scripts', element: <ScriptsPage /> },
  { path: '/projects/:projectId/scripts/:scriptId/storyboard', element: <StoryboardRoute /> },
  { path: '*', element: <Navigate to="/projects" replace /> }
])

export function App() { return <AuthProvider><ApiProvider><RouterProvider router={router} /></ApiProvider></AuthProvider> }
