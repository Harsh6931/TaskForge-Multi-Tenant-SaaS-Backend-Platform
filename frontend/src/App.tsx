import { BrowserRouter, Routes, Route } from "react-router-dom";
import LandingPage from "./pages/LandingPage";

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<LandingPage />} />
        {/* Phase 2+: Auth, Dashboard, Projects, Tasks routes go here */}
      </Routes>
    </BrowserRouter>
  );
}
