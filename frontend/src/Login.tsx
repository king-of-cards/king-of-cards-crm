
import { useState, type FormEvent } from "react";
import "./Login.css";

type LoginProps = {
  onLogin: () => void;
};

export default function Login({ onLogin }: LoginProps) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onLogin();
  }

  return (
    <main className="login-page">
      <section className="login-card">
        <img
          src="/Koc_logo.png"
          alt="King of Cards"
          className="login-logo"
          onError={(event) => {
            event.currentTarget.style.display = "none";
          }}
        />

        <h1 className="login-title">King of Cards</h1>
        <p className="login-subtitle">Cross-Selling CRM</p>

        <form className="login-form" onSubmit={handleSubmit}>
          <label htmlFor="username">Username</label>
          <input
            id="username"
            type="text"
            placeholder="Enter username"
            autoComplete="username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            required
          />

          <label htmlFor="password">Password</label>
          <div className="password-wrapper">
            <input
              id="password"
              type={showPassword ? "text" : "password"}
              placeholder="Enter password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
            />
            <button
              type="button"
              className="password-toggle"
              onClick={() => setShowPassword((previous) => !previous)}
            >
              {showPassword ? "Hide" : "Show"}
            </button>
          </div>

          <button className="login-submit" type="submit">
            Sign In
          </button>
        </form>
      </section>
    </main>
  );
}
