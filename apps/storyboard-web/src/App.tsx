import { Navigate, RouterProvider, createBrowserRouter } from 'react-router-dom'
import { ApiProvider } from './api/api-context'
import { AuthProvider } from './auth'
import { LoginPage } from './pages/LoginPage'
import { ProjectsPage } from './pages/ProjectsPage'
import { ScriptsPage } from './pages/ScriptsPage'
import { StoryboardWorkspacePage } from './pages/StoryboardWorkspacePage'

const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  { path: '/projects', element: <ProjectsPage /> },
  { path: '/projects/:projectId/scripts', element: <ScriptsPage /> },
  { path: '/projects/:projectId/scripts/:scriptId/storyboard', element: <StoryboardWorkspacePage /> },
  { path: '*', element: <Navigate to="/projects" replace /> }
])

export function App() { return <AuthProvider><ApiProvider><RouterProvider router={router} /></ApiProvider></AuthProvider> }
